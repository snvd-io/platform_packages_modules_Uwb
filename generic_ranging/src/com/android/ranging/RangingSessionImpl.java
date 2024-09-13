/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ranging;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.ranging.RangingParameters.DeviceRole;
import com.android.ranging.RangingUtils.StateMachine;
import com.android.ranging.cs.CsAdapter;
import com.android.ranging.fusion.FusionEngine;
import com.android.ranging.uwb.UwbAdapter;
import com.android.ranging.uwb.backend.internal.RangingCapabilities;
import com.android.ranging.uwb.backend.internal.UwbAddress;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.errorprone.annotations.DoNotCall;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**  Implementation of the Android multi-technology ranging layer */
public final class RangingSessionImpl implements RangingSession {

    private static final String TAG = RangingSessionImpl.class.getSimpleName();

    /**
     * Default frequency of the task running the periodic update when {@link
     * RangingConfig#getMaxUpdateInterval} is set to 0.
     */
    private static final long DEFAULT_INTERNAL_UPDATE_INTERVAL_MS = 100;

    private final Context mContext;
    private final RangingConfig mConfig;

    /** Callback for session events. Invariant: Non-null while a session is ongoing */
    private RangingSession.Callback mCallback;

    /** Keeps track of state of the ranging session */
    private final StateMachine<State> mStateMachine;

    /**
     * Ranging adapters used for this session.
     * Must be thread safe. If you must synchronize on mAdapters and mStateMachine, make sure
     * mAdapters is the outer block, otherwise deadlock could occur!
     */
    private final Map<RangingTechnology, RangingAdapter> mAdapters;
    /** Must be thread safe */
    private final Map<RangingTechnology, RangingAdapter.Callback> mAdapterListeners;

    /** Fusion engine to use for this session. */
    private final FusionEngine mFusionEngine;

    /**
     * The executor where periodic updater is executed. Periodic updater updates the caller with
     * new data if available and stops precision ranging if stopping conditions are met. Periodic
     * updater doesn't report new data if config.getMaxUpdateInterval is 0, in that case updates
     * happen immediately after new data is received.
     */
    private final ScheduledExecutorService mPeriodicUpdateExecutor;

    /**
     * Executor service for running async tasks such as starting/stopping individual ranging
     * adapters and fusion algorithm. Most of the results of running the tasks are received via
     * listeners.
     */
    private final ListeningExecutorService mAdapterExecutor;

    /** Last data received that has not yet been reported */
    private RangingData mLastDataReceived;

    /** Timestamp when last data was received */
    private Instant mLastDataReceivedTime;

    /**
     * Start time is used to check if we're in a grace period right after starting so we don't stop
     * ranging before giving it a chance to start producing data.
     */
    private Instant mStartTime;

    public RangingSessionImpl(
            @NonNull Context context,
            @NonNull RangingConfig config,
            @NonNull FusionEngine fusionEngine,
            @NonNull ScheduledExecutorService periodicUpdateExecutor,
            @NonNull ListeningExecutorService rangingAdapterExecutor
    ) {
        mContext = context;
        mConfig = config;

        mStateMachine = new StateMachine<>(State.STOPPED);
        mCallback = null;

        mAdapters = Collections.synchronizedMap(new EnumMap<>(RangingTechnology.class));
        mAdapterListeners = Collections.synchronizedMap(new EnumMap<>(RangingTechnology.class));
        mFusionEngine = fusionEngine;

        mPeriodicUpdateExecutor = periodicUpdateExecutor;
        mAdapterExecutor = rangingAdapterExecutor;

        mLastDataReceived = null;
        mStartTime = Instant.EPOCH;
        mLastDataReceivedTime = Instant.EPOCH;
    }

    private @NonNull RangingAdapter newAdapter(
            @NonNull RangingTechnology technology, DeviceRole role
    ) {
        switch (technology) {
            case UWB:
                return new UwbAdapter(mContext, mAdapterExecutor, role);
            case CS:
                return new CsAdapter();
            default:
                throw new IllegalArgumentException(
                        "Tried to create adapter for unknown technology" + technology);
        }
    }

    @Override
    public void start(@NonNull RangingParameters parameters, @NonNull Callback callback) {
        EnumMap<RangingTechnology, RangingParameters.TechnologyParameters> paramsMap =
                parameters.asMap();
        mAdapters.keySet().retainAll(paramsMap.keySet());

        Log.i(TAG, "Start Precision Ranging called.");
        if (!mStateMachine.transition(State.STOPPED, State.STARTING)) {
            Log.w(TAG, "Failed transition STOPPED -> STARTING");
            return;
        }
        mCallback = callback;

        for (RangingTechnology technology : paramsMap.keySet()) {
            if (!technology.isSupported(mContext)) {
                Log.w(TAG, "Attempted to range with unsupported technology " + technology
                        + ", skipping");
                continue;
            }

            // Do not overwrite any adapters that were supplied for testing
            if (!mAdapters.containsKey(technology)) {
                mAdapters.put(technology, newAdapter(technology, parameters.getRole()));
            }

            AdapterListener listener = new AdapterListener(technology);
            mAdapterListeners.put(technology, listener);
            mAdapters.get(technology).start(paramsMap.get(technology), listener);
        }

        mFusionEngine.start(new FusionEngineListener());

        mStartTime = Instant.now();
        Log.i(TAG, "Starting periodic update. Start time: " + mStartTime);

        long periodicUpdateIntervalMs = mConfig.getMaxUpdateInterval().isZero()
                ? DEFAULT_INTERNAL_UPDATE_INTERVAL_MS
                : mConfig.getMaxUpdateInterval().toMillis();
        var unused = mPeriodicUpdateExecutor.scheduleWithFixedDelay(
                this::performPeriodicUpdate, 0, periodicUpdateIntervalMs, MILLISECONDS);
    }

    /*
     * Periodic updater reports new data via the callback and stops precision ranging if
     * stopping conditions are met.
     */
    private void performPeriodicUpdate() {
        if (!mConfig.getMaxUpdateInterval().isZero()) {
            reportNewDataIfAvailable();
        }
        checkAndStopIfNeeded();
    }

    /* Reports new data if available via the callback. */
    private void reportNewDataIfAvailable() {
        synchronized (mStateMachine) {
            if (mLastDataReceived != null && mStateMachine.getState() == State.STARTED) {
                mCallback.onData(mLastDataReceived);
                mLastDataReceived = null;
            }
        }
    }

    /* Checks if stopping conditions are met and if so, stops precision ranging. */
    private void checkAndStopIfNeeded() {
        Instant now = Instant.now();
        // If we're still inside the init timeout don't stop ranging.
        if (now.isBefore(mStartTime.plus(mConfig.getInitTimeout()))) {
            return;
        }

        // If we didn't receive data from any source for more than the update timeout then stop.
        if (now.isAfter(mLastDataReceivedTime.plus(mConfig.getNoUpdateTimeout()))) {
            Log.i(TAG, "No data received for configured timeout of "
                    + mConfig.getNoUpdateTimeout().toMillis() + " ms, stopping ranging session.");
            stopPrecisionRanging(Callback.StoppedReason.EMPTY_SESSION_TIMEOUT);
        }
    }

    @Override
    public void stop() {
        stopPrecisionRanging(RangingAdapter.Callback.StoppedReason.REQUESTED);
    }

    /**
     * Calls stop on all ranging adapters and the fusion algorithm and resets all internal states.
     */
    private void stopPrecisionRanging(@Callback.StoppedReason int reason) {
        Log.i(TAG, "stopPrecisionRanging with reason: " + reason);
        synchronized (mStateMachine) {
            if (mStateMachine.getState() == State.STOPPED) {
                Log.v(TAG, "Ranging already stopped, skipping");
                return;
            }
            mStateMachine.setState(State.STOPPED);
        }
        // stop all ranging techs
        synchronized (mAdapters) {
            for (RangingTechnology technology : mAdapters.keySet()) {
                mAdapters.get(technology).stop();
                mCallback.onStopped(technology, reason);
            }
        }

        mFusionEngine.stop();

        mCallback.onStopped(null, reason);

        // reset internal states and objects
        mLastDataReceivedTime = Instant.EPOCH;
        mStartTime = Instant.EPOCH;
        mAdapters.clear();
        mAdapterListeners.clear();
        mCallback = null;
        mLastDataReceived = null;
    }

    @Override
    public ListenableFuture<RangingCapabilities> getUwbCapabilities() {
        if (!mAdapters.containsKey(RangingTechnology.UWB)) {
            return immediateFailedFuture(
                    new IllegalStateException("UWB was not requested for this session."));
        }
        UwbAdapter uwbAdapter = (UwbAdapter) mAdapters.get(RangingTechnology.UWB);
        try {
            return uwbAdapter.getCapabilities();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get Uwb capabilities");
            return null;
        }
    }

    @Override
    public ListenableFuture<UwbAddress> getUwbAddress() throws RemoteException {
        if (!mAdapters.containsKey(RangingTechnology.UWB)) {
            return immediateFailedFuture(
                    new IllegalStateException("UWB was not requested for this session."));
        }
        UwbAdapter uwbAdapter = (UwbAdapter) mAdapters.get(RangingTechnology.UWB);
        return uwbAdapter.getLocalAddress();
    }

    @DoNotCall("Not implemented")
    @Override
    public void getCsCapabilities() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ListenableFuture<EnumMap<RangingTechnology, Integer>> getTechnologyStatus() {
        // Combine all isEnabled futures for each technology into a single future. The resulting
        // future contains a list of technologies grouped with their corresponding
        // enabled state.
        ListenableFuture<List<Map.Entry<RangingTechnology, Boolean>>> enabledStatesFuture;
        synchronized (mAdapters) {
            enabledStatesFuture = Futures.allAsList(mAdapters.entrySet().stream()
                    .map((var entry) -> Futures.transform(
                            entry.getValue().isEnabled(),
                            (Boolean isEnabled) -> Map.entry(entry.getKey(), isEnabled),
                            mAdapterExecutor)
                    )
                    .collect(Collectors.toList())
            );
        }

        // Transform the list of enabled states into a technology status map.
        return Futures.transform(
                enabledStatesFuture,
                (List<Map.Entry<RangingTechnology, Boolean>> enabledStates) -> {
                    EnumMap<RangingTechnology, Integer> statuses =
                            new EnumMap<>(RangingTechnology.class);
                    for (RangingTechnology technology : RangingTechnology.values()) {
                        statuses.put(technology, TechnologyStatus.UNUSED);
                    }

                    for (Map.Entry<RangingTechnology, Boolean> enabledState : enabledStates) {
                        RangingTechnology technology = enabledState.getKey();
                        if (enabledState.getValue()) {
                            statuses.put(technology, TechnologyStatus.ENABLED);
                        } else {
                            statuses.put(technology, TechnologyStatus.DISABLED);
                        }
                    }
                    return statuses;
                },
                mAdapterExecutor
        );
    }

//    @VisibleForTesting
//    public Optional<MultiSensorFinderListener> getFusionAlgorithmListener() {
//        return fusionAlgorithmListener;
//    }

    /* Listener implementation for ranging adapter callback. */
    private class AdapterListener implements RangingAdapter.Callback {
        private final RangingTechnology mTechnology;

        AdapterListener(RangingTechnology technology) {
            this.mTechnology = technology;
        }

        @Override
        public void onStarted() {
            synchronized (mStateMachine) {
                if (mStateMachine.getState() == State.STOPPED) {
                    Log.w(TAG, "Received adapter onStarted but ranging session is stopped");
                    return;
                }
                if (mStateMachine.transition(State.STARTING, State.STARTED)) {
                    // The first adapter in the session has started, so start the session.
                    mCallback.onStarted(null);
                }
            }
            mFusionEngine.addDataSource(mTechnology);
            mCallback.onStarted(mTechnology);
        }

        @Override
        public void onStopped(@RangingAdapter.Callback.StoppedReason int reason) {
            synchronized (mAdapters) {
                if (mStateMachine.getState() != State.STOPPED) {
                    mAdapters.remove(mTechnology);
                    mAdapterListeners.remove(mTechnology);
                    mFusionEngine.removeDataSource(mTechnology);
                    mCallback.onStopped(mTechnology, reason);
                }
            }
        }

        @Override
        public void onRangingData(RangingData data) {
            synchronized (mStateMachine) {
                if (mStateMachine.getState() != State.STOPPED) {
                    mFusionEngine.feed(data);
                }
            }
        }
    }

    /* Listener implementation for fusion engine callback. */
    private class FusionEngineListener implements FusionEngine.Callback {

        @Override
        public void onData(@NonNull RangingData data) {
            synchronized (mStateMachine) {
                if (mStateMachine.getState() == State.STOPPED) {
                    return;
                }
                mLastDataReceivedTime = Instant.now();
                if (mConfig.getMaxUpdateInterval().isZero()) {
                    mCallback.onData(data);
                } else {
                    mLastDataReceived = data;
                }
            }
        }
    }

    @VisibleForTesting
    public void useAdapterForTesting(RangingTechnology technology, RangingAdapter adapter) {
        mAdapters.put(technology, adapter);
    }

    private enum State {
        STARTING,
        STARTED,
        STOPPED,
    }
}
