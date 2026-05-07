package com.kooo.evcam.v2.settings

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.Size
import android.view.WindowManager
import com.kooo.evcam.v2.log.V2AppLog

object V2RecordingSettings {
    private const val PREFS = "evcam_v2_recording_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_BITRATE_LEVEL = "bitrate_level"
    private const val KEY_FPS = "fps"
    private const val KEY_SEGMENT_MINUTES = "segment_minutes"

    private const val FALLBACK_RESOLUTION = "1280x720"
    private const val COMPOSITE_COLUMNS = 2
    private const val COMPOSITE_ROWS = 2
    private const val COMPOSITE_CAMERA_COUNT = COMPOSITE_COLUMNS * COMPOSITE_ROWS
    private const val DEFAULT_FPS = 15
    private const val BASE_SINGLE_CAMERA_BITRATE_720P = 4_000_000L
    private const val MIN_SINGLE_CAMERA_BITRATE = 2_000_000L
    private const val MAX_SINGLE_CAMERA_BITRATE = 20_000_000L
    private const val MAX_COMPOSITE_BITRATE = MAX_SINGLE_CAMERA_BITRATE * COMPOSITE_CAMERA_COUNT
    const val BITRATE_LOW = "low"
    const val BITRATE_MEDIUM = "medium"
    const val BITRATE_HIGH = "high"

    val bitrateOptions = listOf(
        Option(BITRATE_LOW, "低"),
        Option(BITRATE_MEDIUM, "标准"),
        Option(BITRATE_HIGH, "高")
    )
    val fpsOptions = listOf(15, 25)
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
        val supported = V2CameraCapabilityResolver.allSupportedSurfaceTextureSizes(context)
        val options = supported.map { Option(valueForSize(it), "${it.width}×${it.height}") }
        if (options.isNotEmpty()) return options
        val fallback = listOf(Size(1280, 720))
        return fallback.map { Option(valueForSize(it), "${it.width}×${it.height}") }
    }

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
        val screen = screenSize(context)
        val size = recordingSize(context, screen)
        return bitrateOptions.map { option ->
            val single = singleCameraBitrateForLevel(size, option.value)
            val composite = compositeBitrateForLevel(size, option.value)
            Option(option.value, "${option.label}（单路${formatMbps(single)}Mbps / 合成${formatMbps(composite)}Mbps）")
        }
    }

    fun summary(context: Context): String = V2SettingsFormatter.recordingSummary(context)

    fun bitrateForLevel(size: Size, level: String): Int = singleCameraBitrateForLevel(size, level)

    fun singleCameraBitrateForLevel(size: Size, level: String): Int {
        val basePixels = 1280L * 720L
        val pixels = size.width.toLong() * size.height.toLong()
        val auto = ((BASE_SINGLE_CAMERA_BITRATE_720P * pixels) / basePixels)
            .coerceAtLeast(BASE_SINGLE_CAMERA_BITRATE_720P)
            .coerceAtMost(MAX_SINGLE_CAMERA_BITRATE)
        val scaled = when (level) {
            BITRATE_LOW -> (auto * 0.75).toLong()
            BITRATE_HIGH -> (auto * 1.5).toLong()
            else -> auto
        }
        return scaled.coerceAtLeast(MIN_SINGLE_CAMERA_BITRATE).coerceAtMost(MAX_SINGLE_CAMERA_BITRATE).toInt()
    }

    fun compositeBitrateForLevel(cameraSize: Size, level: String): Int =
        (singleCameraBitrateForLevel(cameraSize, level).toLong() * COMPOSITE_CAMERA_COUNT)
            .coerceAtMost(MAX_COMPOSITE_BITRATE)
            .toInt()

    fun compositeOutputSize(cameraSize: Size): Size = evenSize(cameraSize)

    fun screenSize(context: Context): Size {
        val fallback = Size(2560, 1600)
        return runCatching {
            val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Size(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                Size(point.x, point.y)
            }
            evenSize(raw)
        }.getOrDefault(fallback)
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
    private fun evenSize(size: Size) = Size(
        size.width.coerceAtLeast(2).let { it - it % 2 },
        size.height.coerceAtLeast(2).let { it - it % 2 },
    )
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Option(val value: String, val label: String)
}
