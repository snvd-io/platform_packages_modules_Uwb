/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.uwb.secure.csml;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.uwb.secure.iso7816.ResponseApdu;
import com.android.server.uwb.secure.iso7816.StatusWord;
import com.android.server.uwb.secure.iso7816.TlvDatum;
import com.android.server.uwb.util.DataTypeConversionUtil;

import org.junit.Test;

public class TunnelResponseTest {
    @Test
    public void validResponseData() {
        TlvDatum subDataTlv = new TlvDatum(TunnelResponse.DATA_TAG,
                DataTypeConversionUtil.hexStringToByteArray("0A0B"));
        TlvDatum dataTlv = new TlvDatum(FiRaResponse.PROPRIETARY_RESPONSE_TAG, subDataTlv);
        ResponseApdu responseApdu = ResponseApdu.fromDataAndStatusWord(
                dataTlv.toBytes(), StatusWord.SW_NO_ERROR.toInt());
        TunnelResponse tunnelResponse = TunnelResponse.fromResponseApdu(responseApdu);

        assertThat(tunnelResponse.isSuccess()).isTrue();
        assertThat(tunnelResponse.outboundDataOrApdu.get())
                .isEqualTo(DataTypeConversionUtil.hexStringToByteArray("0A0B"));
    }

    @Test
    public void wrongStatusWord() {
        ResponseApdu responseApdu = ResponseApdu.fromStatusWord(
                StatusWord.SW_CONDITIONS_NOT_SATISFIED);
        TunnelResponse tunnelResponse = TunnelResponse.fromResponseApdu(responseApdu);

        assertThat(tunnelResponse.isSuccess()).isFalse();
        assertThat(tunnelResponse.outboundDataOrApdu.isEmpty()).isTrue();
    }

    @Test
    public void wrongTopTag() {
        TlvDatum subDataTlv = new TlvDatum(TunnelResponse.DATA_TAG,
                DataTypeConversionUtil.hexStringToByteArray("0A0B"));
        TlvDatum dataTlv = new TlvDatum(new TlvDatum.Tag((byte) 0x06), subDataTlv);
        ResponseApdu responseApdu = ResponseApdu.fromDataAndStatusWord(
                dataTlv.toBytes(), StatusWord.SW_NO_ERROR.toInt());
        TunnelResponse tunnelResponse = TunnelResponse.fromResponseApdu(responseApdu);

        assertThat(tunnelResponse.isSuccess()).isTrue();
        assertThat(tunnelResponse.outboundDataOrApdu.isEmpty()).isTrue();
    }

    @Test
    public void wrongDataTag() {
        TlvDatum subDataTlv = new TlvDatum(new TlvDatum.Tag((byte) 0x01),
                DataTypeConversionUtil.hexStringToByteArray("0A0B"));
        TlvDatum dataTlv = new TlvDatum(FiRaResponse.PROPRIETARY_RESPONSE_TAG, subDataTlv);
        ResponseApdu responseApdu = ResponseApdu.fromDataAndStatusWord(
                dataTlv.toBytes(), StatusWord.SW_NO_ERROR.toInt());
        TunnelResponse tunnelResponse = TunnelResponse.fromResponseApdu(responseApdu);

        assertThat(tunnelResponse.isSuccess()).isTrue();
        assertThat(tunnelResponse.outboundDataOrApdu.isEmpty()).isTrue();
    }
}
