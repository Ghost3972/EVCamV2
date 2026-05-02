package com.kooo.evcam.v2.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import com.kooo.evcam.v2.log.V2AppLog

object V2EncoderCapabilityLogger {
    private const val TAG = "V2EncoderCaps"
    private val VIDEO_MIME_TYPES = listOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
    )
    private val SAMPLE_SIZES = listOf(
        1280 to 720,
        1920 to 1080,
        2560 to 1440,
        3840 to 2160,
    )

    @Volatile private var logged = false

    fun logOnce() {
        if (logged) return
        logged = true
        logVideoEncoderCapabilities()
    }

    fun logVideoEncoderCapabilities() {
        runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            V2AppLog.i(TAG, "video encoder capability scan begin encoderCount=${codecs.size}")
            VIDEO_MIME_TYPES.forEach { mime -> logMimeCapabilities(codecs, mime) }
            V2AppLog.i(TAG, "video encoder capability scan end")
        }.onFailure { error ->
            V2AppLog.e(TAG, "video encoder capability scan failed", error)
        }
    }

    private fun logMimeCapabilities(codecs: List<MediaCodecInfo>, mime: String) {
        val supported = codecs.filter { codec -> codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        V2AppLog.i(TAG, "mime=$mime encoderCount=${supported.size}")
        supported.forEach { codec -> logCodecCapabilities(codec, mime) }
    }

    private fun logCodecCapabilities(codec: MediaCodecInfo, mime: String) {
        runCatching {
            val caps = codec.getCapabilitiesForType(mime)
            val videoCaps = caps.videoCapabilities
            val encoderCaps = caps.encoderCapabilities
            V2AppLog.i(
                TAG,
                buildString {
                    append("encoder name=${codec.name}")
                    append(" mime=$mime")
                    append(" hardware=${isHardwareAccelerated(codec)}")
                    append(" software=${isSoftwareOnly(codec)}")
                    append(" vendor=${isVendor(codec)}")
                    append(" maxInstances=${caps.maxSupportedInstances}")
                    append(" widths=${videoCaps?.supportedWidths?.format() ?: "unknown"}")
                    append(" heights=${videoCaps?.supportedHeights?.format() ?: "unknown"}")
                    append(" bitrate=${videoCaps?.bitrateRange?.format() ?: "unknown"}")
                    append(" complexity=${encoderCaps?.complexityRange?.format() ?: "unknown"}")
                    append(" quality=${encoderCaps?.qualityRange?.format() ?: "unknown"}")
                    append(" bitrateModes=${encoderCaps?.let { bitrateModes(it) } ?: "unknown"}")
                    append(" colorFormats=${caps.colorFormats.joinToString(prefix = "[", postfix = "]")}")
                }
            )
            videoCaps?.let { capabilities ->
                SAMPLE_SIZES.forEach { (width, height) -> logSampleSize(capabilities, codec.name, mime, width, height) }
            }
        }.onFailure { error ->
            V2AppLog.w(TAG, "encoder capability failed name=${codec.name} mime=$mime", error)
        }
    }

    private fun logSampleSize(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        codecName: String,
        mime: String,
        width: Int,
        height: Int,
    ) {
        val supported = runCatching { videoCaps.isSizeSupported(width, height) }.getOrDefault(false)
        val fpsRange = if (supported) {
            runCatching { videoCaps.getSupportedFrameRatesFor(width, height).format() }.getOrElse { "unknown:${it.javaClass.simpleName}" }
        } else {
            "unsupported"
        }
        V2AppLog.i(TAG, "encoderSample name=$codecName mime=$mime size=${width}x$height supported=$supported fps=$fpsRange")
    }

    private fun isHardwareAccelerated(codec: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codec.isHardwareAccelerated else !looksLikeSoftwareCodec(codec.name)

    private fun isSoftwareOnly(codec: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codec.isSoftwareOnly else looksLikeSoftwareCodec(codec.name)

    private fun isVendor(codec: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codec.isVendor else !looksLikeSoftwareCodec(codec.name)

    private fun looksLikeSoftwareCodec(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains("sw") || lower.contains("software")
    }

    private fun bitrateModes(caps: MediaCodecInfo.EncoderCapabilities): String {
        val modes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ to "CQ",
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
        ).filter { (mode, _) -> caps.isBitrateModeSupported(mode) }.map { it.second }
        return modes.joinToString(prefix = "[", postfix = "]")
    }

    private fun <T : Comparable<T>> Range<T>.format(): String = "${lower}..${upper}"
}
