package com.kooo.evcam.v2.settings

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog

object V2RecordingSettings {
    private const val PREFS = "evcam_v2_recording_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_BITRATE_LEVEL = "bitrate_level"
    private const val KEY_FPS = "fps"
    private const val KEY_SEGMENT_MINUTES = "segment_minutes"

    private const val FALLBACK_RESOLUTION = "1280x720"
    private const val MAX_PLAYBACK_COMPOSITE_WIDTH = 2560
    private const val MAX_PLAYBACK_COMPOSITE_HEIGHT = 1440
    private const val COMPOSITE_COLUMNS = 2
    private const val COMPOSITE_ROWS = 2
    private const val DEFAULT_FPS = 15
    const val BITRATE_LOW = "low"
    const val BITRATE_MEDIUM = "medium"
    const val BITRATE_HIGH = "high"

    val bitrateOptions = listOf(
        Option(BITRATE_LOW, "低"),
        Option(BITRATE_MEDIUM, "标准"),
        Option(BITRATE_HIGH, "高")
    )
    val fpsOptions = listOf(15)
    val segmentMinuteOptions = listOf(1, 3, 5, 10)

    fun resolution(context: Context): String {
        val options = supportedResolutionOptions(context)
        val saved = prefs(context).getString(KEY_RESOLUTION, null)
        val next = options.firstOrNull { it.value == saved }?.value
            ?: options.firstOrNull()?.value
            ?: FALLBACK_RESOLUTION
        if (saved != next) prefs(context).edit().putString(KEY_RESOLUTION, next).apply()
        return next
    }
    fun bitrateLevel(context: Context): String = prefs(context).getString(KEY_BITRATE_LEVEL, BITRATE_MEDIUM) ?: BITRATE_MEDIUM
    fun fps(context: Context): Int = fpsOptions.minByOrNull {
        kotlin.math.abs(it - prefs(context).getInt(KEY_FPS, DEFAULT_FPS).coerceIn(15, 30))
    } ?: DEFAULT_FPS
    fun segmentMinutes(context: Context): Int = prefs(context).getInt(KEY_SEGMENT_MINUTES, 1).coerceAtLeast(1)
    fun segmentDurationMs(context: Context): Long = segmentMinutes(context) * 60_000L

    fun setResolution(context: Context, value: String) {
        val next = supportedResolutionOptions(context).firstOrNull { it.value == value }?.value
            ?: supportedResolutionOptions(context).firstOrNull()?.value
            ?: FALLBACK_RESOLUTION
        prefs(context).edit().putString(KEY_RESOLUTION, next).apply()
        V2AppLog.i("V2RecordingSettings", "resolution=$next")
    }

    fun setBitrateLevel(context: Context, value: String) {
        val next = bitrateOptions.firstOrNull { it.value == value }?.value ?: BITRATE_MEDIUM
        prefs(context).edit().putString(KEY_BITRATE_LEVEL, next).apply()
        V2AppLog.i("V2RecordingSettings", "bitrateLevel=$next")
    }

    fun setFps(context: Context, value: Int) {
        val next = fpsOptions.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_FPS
        prefs(context).edit().putInt(KEY_FPS, next).apply()
        V2AppLog.i("V2RecordingSettings", "fps=$next")
    }

    fun setSegmentMinutes(context: Context, value: Int) {
        val next = segmentMinuteOptions.minByOrNull { kotlin.math.abs(it - value) } ?: 1
        prefs(context).edit().putInt(KEY_SEGMENT_MINUTES, next).apply()
        V2AppLog.i("V2RecordingSettings", "segmentMinutes=$next")
    }

    fun supportedResolutionOptions(context: Context): List<Option> {
        val supported = V2CameraCapabilityResolver.commonSupportedSurfaceTextureSizes(context)
        val safeSupported = supported.filter { isPlaybackSafeCompositeSize(it) }
        val options = safeSupported.map { Option(valueForSize(it), "${it.width}×${it.height}") }
        if (options.isNotEmpty()) return options
        val fallback = listOf(Size(1280, 720))
        return fallback.map { Option(valueForSize(it), "${it.width}×${it.height}") }
    }

    private fun maxSupportedResolution(context: Context): String = supportedResolutionOptions(context).firstOrNull()?.value ?: FALLBACK_RESOLUTION

    fun recordingSize(context: Context, screenSize: Size): Size {
        val selected = parseSize(resolution(context))
        val supported = supportedResolutionOptions(context).mapNotNull { parseSize(it.value) }
        return when {
            selected != null && supported.any { it.width == selected.width && it.height == selected.height } -> selected
            supported.isNotEmpty() -> supported.first()
            selected != null -> selected
            else -> evenSize(screenSize)
        }
    }

    fun bitrate(context: Context, size: Size): Int = bitrateForLevel(size, bitrateLevel(context))

    fun bitrateOptionsWithMbps(context: Context): List<Option> {
        val size = recordingSize(context, Size(1280, 720))
        return bitrateOptions.map { option ->
            Option(option.value, "${option.label}（${formatMbps(bitrateForLevel(size, option.value))}Mbps）")
        }
    }

    fun summary(context: Context): String = V2SettingsFormatter.recordingSummary(context)

    fun bitrateForLevel(size: Size, level: String): Int {
        val basePixels = 1280L * 720L
        val pixels = size.width.toLong() * size.height.toLong()
        val auto = ((2_500_000L * pixels) / basePixels).coerceAtLeast(2_500_000L).coerceAtMost(30_000_000L)
        val scaled = when (level) {
            BITRATE_LOW -> (auto * 0.7).toLong()
            BITRATE_HIGH -> (auto * 1.5).toLong()
            else -> auto
        }
        return scaled.coerceAtLeast(1_500_000L).coerceAtMost(45_000_000L).toInt()
    }

    private fun formatMbps(bitsPerSecond: Int): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return if (mbps >= 10 || mbps % 1.0 == 0.0) String.format(java.util.Locale.US, "%.0f", mbps) else String.format(java.util.Locale.US, "%.1f", mbps)
    }

    fun sizeFromValue(value: String): Size? = parseSize(value)

    private fun parseSize(value: String): Size? {
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) Size(width, height) else null
    }
    private fun valueForSize(size: Size) = "${size.width}x${size.height}"
    private fun evenSize(size: Size) = Size(size.width - size.width % 2, size.height - size.height % 2)
    private fun isPlaybackSafeCompositeSize(size: Size): Boolean =
        size.width * COMPOSITE_COLUMNS <= MAX_PLAYBACK_COMPOSITE_WIDTH &&
            size.height * COMPOSITE_ROWS <= MAX_PLAYBACK_COMPOSITE_HEIGHT
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Option(val value: String, val label: String)
}
