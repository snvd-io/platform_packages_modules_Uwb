/*
 * Copyright 2023 The Android Open Source Project
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

package android.uwb;

import android.uwb.StateChangeReason;
import android.uwb.UwbActivityEnergyInfo;

/**
 * Interface for Uwb activity energy info listener
 *
 * @hide
 */
oneway interface IOnUwbActivityEnergyInfoListener {
    /**
     * Service to manager callback providing current Uwb activity energy info.
     * @param info the Uwb activity energy info
     */
    void onUwbActivityEnergyInfo(in UwbActivityEnergyInfo info);
}