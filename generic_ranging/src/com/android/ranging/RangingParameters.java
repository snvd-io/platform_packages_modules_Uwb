/*
 * Copyright 2024 The Android Open Source Project
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

import androidx.annotation.NonNull;
import androidx.core.uwb.backend.impl.internal.UwbAddress;
import androidx.core.uwb.backend.impl.internal.UwbComplexChannel;
import androidx.core.uwb.backend.impl.internal.UwbRangeDataNtfConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/** Parameters for a generic ranging session. */
public class RangingParameters {
    /** Parameters for a specific generic ranging technology. */
    public interface TechnologyParameters { }

    /** Parameters for UWB ranging. */
    public static class UwbParameters
            extends androidx.core.uwb.backend.impl.internal.RangingParameters
            implements TechnologyParameters {

        public UwbParameters(int uwbConfigId, int sessionId, int subSessionId,
                byte[] sessionKeyInfo,
                byte[] subSessionKeyInfo,
                UwbComplexChannel complexChannel,
                List<UwbAddress> peerAddresses,
                int rangingUpdateRate,
                @NonNull UwbRangeDataNtfConfig uwbRangeDataNtfConfig,
                int slotDuration, boolean isAoaDisabled) {
            super(uwbConfigId, sessionId, subSessionId, sessionKeyInfo, subSessionKeyInfo,
                    complexChannel,
                    peerAddresses, rangingUpdateRate, uwbRangeDataNtfConfig, slotDuration,
                    isAoaDisabled);
        }
    }

    /** Parameters for Bluetooth channel sounding ranging. */
    public static class CsParameters implements TechnologyParameters {
        public CsParameters() {
            throw new UnsupportedOperationException("Not implemented!");
        }
    }

    private final EnumMap<RangingTechnology, TechnologyParameters> mParameters;

    private RangingParameters(@NonNull RangingParameters.Builder builder) {
        mParameters = new EnumMap<>(RangingTechnology.class);
        if (builder.mUwbParameters != null) {
            mParameters.put(RangingTechnology.UWB, builder.mUwbParameters);
        }
        if (builder.mCsParameters != null) {
            mParameters.put(RangingTechnology.CS, builder.mCsParameters);
        }
    }

    /**
     * @return UWB parameters, or {@code Optional.empty()} if they were never set.
     */
    public Optional<UwbParameters> getUwbParameters() {
        return Optional.ofNullable(mParameters.get(RangingTechnology.UWB))
                .map(params -> (UwbParameters) params);
    }

    /**
     * @return channel sounding parameters, or {@code Optional.empty()} if they were never set.
     */
    public Optional<CsParameters> getCsParameters() {
        return Optional.ofNullable(mParameters.get(RangingTechnology.CS))
                .map(params -> (CsParameters) params);
    }

    /** @return A map between technologies and their corresponding generic parameters object. */
    public @NonNull EnumMap<RangingTechnology, TechnologyParameters> asMap() {
        return mParameters.clone();
    }

    public static class Builder {
        private UwbParameters mUwbParameters = null;
        private CsParameters mCsParameters = null;

        /** Build the {@link RangingParameters object} */
        public RangingParameters build() {
            return new RangingParameters(this);
        }

        /**
         * Range with UWB in this session.
         * @param uwbParameters containing a configuration for UWB ranging.
         */
        public Builder useUwb(@NonNull UwbParameters uwbParameters) {
            mUwbParameters = uwbParameters;
            return this;
        }

        /**
         * Range with Bluetooth Channel Sounding in this session.
         * @param csParameters containing a configuration for CS ranging.
         */
        public Builder useCs(@NonNull CsParameters csParameters) {
            mCsParameters = csParameters;
            return this;
        }
    }
}
