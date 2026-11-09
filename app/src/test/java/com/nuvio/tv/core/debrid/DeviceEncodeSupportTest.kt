package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridStreamEncode
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEncodeSupportTest {

    @Test
    fun `encodes without a mime mapping are never ruled out`() {
        // XviD, DivX and Unknown are container-era labels with no MediaCodec mime to test, so they
        // must not be pushed behind playable encodes on the strength of a lookup we never made.
        assertTrue(DeviceEncodeSupport.isDecodable(DebridStreamEncode.XVID))
        assertTrue(DeviceEncodeSupport.isDecodable(DebridStreamEncode.DIVX))
        assertTrue(DeviceEncodeSupport.isDecodable(DebridStreamEncode.UNKNOWN))
    }

    @Test
    fun `ten bit support is assumed when the codec list is unavailable`() {
        // Same fallback as isDecodable: with no media stack to ask, nothing may be demoted.
        DebridStreamEncode.entries.forEach { encode ->
            assertTrue(
                "expected $encode to stay ten-bit playable without a codec list",
                DeviceEncodeSupport.isTenBitDecodable(encode)
            )
        }
    }

    @Test
    fun `a missing codec list leaves every encode playable`() {
        // Unit tests run without an Android media stack, so MediaCodecList throws and the detector
        // falls back to assuming everything decodes. Ranking then behaves exactly as before.
        DebridStreamEncode.entries.forEach { encode ->
            assertTrue(
                "expected $encode to stay playable when the codec list is unavailable",
                DeviceEncodeSupport.isDecodable(encode)
            )
        }
    }
}
