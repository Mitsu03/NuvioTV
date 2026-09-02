package com.nuvio.tv.core.debrid

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import com.nuvio.tv.domain.model.DebridStreamEncode

/**
 * Which video encodes this device can actually decode.
 *
 * Stream preferences rank encodes by taste, and AV1 sits first by default. On hardware without an
 * AV1 decoder that ranking hands the player a stream it cannot open: a 2019 Shield has no AV1
 * decoder at all, and the bundled software fallback refuses to initialise, so playback ends on
 * ERROR_CODE_DECODER_INIT_FAILED before the first frame.
 *
 * Ten-bit H.264 is the same problem one level down. The codec is listed, so a mime-type check says
 * yes, but the decoder advertises Baseline/Main/High only - never High 10 - and the player fails
 * with NO_EXCEEDS_CAPABILITIES on a release that ffprobe calls plain "avc". Ten-bit HEVC is fine on
 * the same hardware, which is why this is judged per codec rather than by bit depth alone.
 *
 * Taste should not outrank capability. Anything with no decoder here is pushed behind every
 * playable stream, while staying selectable as a last resort rather than hiding the title.
 */
object DeviceEncodeSupport {

    private val mimeTypes = mapOf(
        DebridStreamEncode.AV1 to "video/av01",
        DebridStreamEncode.HEVC to "video/hevc",
        DebridStreamEncode.AVC to "video/avc"
    )

    private val decoders: Map<String, Set<Int>> by lazy { detectDecoders() }

    /** Encodes with no decoder on this device. Encodes we cannot test are treated as decodable. */
    fun isDecodable(encode: DebridStreamEncode): Boolean {
        val mimeType = mimeTypes[encode] ?: return true
        return decoders.containsKey(mimeType)
    }

    /**
     * True when this device can decode ten-bit video for [encode].
     *
     * Only meaningful for AVC in practice: HEVC Main 10 is how the platform does HDR, so it is
     * present wherever HEVC is, while AVC High 10 is a niche profile almost no hardware carries.
     */
    fun isTenBitDecodable(encode: DebridStreamEncode): Boolean {
        val mimeType = mimeTypes[encode] ?: return true
        val profiles = decoders[mimeType] ?: return true
        // No profile list means the query failed or the decoder declares none - either way this is
        // ignorance, not absence, and must not demote the stream.
        if (profiles.isEmpty()) return true
        val tenBitProfiles = when (encode) {
            DebridStreamEncode.AVC -> setOf(CodecProfileLevel.AVCProfileHigh10)
            DebridStreamEncode.HEVC -> setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10)
            DebridStreamEncode.AV1 -> setOf(CodecProfileLevel.AV1ProfileMain10)
            else -> return true
        }
        return profiles.any { it in tenBitProfiles }
    }

    private fun detectDecoders(): Map<String, Set<Int>> {
        val wanted = mimeTypes.values.toSet()
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filterNot { it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .map { it.lowercase() }
                        .filter { it in wanted }
                        .map { mimeType ->
                            mimeType to info.getCapabilitiesForType(mimeType)
                                .profileLevels
                                .map { it.profile }
                                .toSet()
                        }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, profileSets) -> profileSets.flatten().toSet() }
        } catch (_: Throwable) {
            // Without a codec list there is nothing to rule out; assume everything plays.
            wanted.associateWith { emptySet<Int>() }
        }
    }
}
