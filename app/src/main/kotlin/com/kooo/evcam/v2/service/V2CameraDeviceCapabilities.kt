package com.kooo.evcam.v2.service

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.WindowManager
import com.kooo.evcam.v2.log.V2AppLog
import kotlin.math.abs

object V2CameraDeviceCapabilities {
    private const val TAG = "V2CameraEngine"

    fun detectScreenSize(context: Context): Size {
        val fallback = Size(2560, 1600)
        return runCatching {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rawSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            rawSize.evenSize()
        }.getOrDefault(fallback)
    }

    fun choosePreviewSize(
        cameraManager: CameraManager,
        cameraId: String,
        targetWidth: Int,
        targetHeight: Int,
    ): Size? = runCatching {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return@runCatching null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return@runCatching null

        sizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
            ?: sizes.filter { it.width <= targetWidth && it.height <= targetHeight }
                .maxWithOrNull(compareBy<Size> { it.width * it.height }.thenBy { it.width })
            ?: sizes.minByOrNull { abs(it.width - targetWidth) + abs(it.height - targetHeight) }
    }.onFailure { V2AppLog.e(TAG, "choosePreviewSize failed camera=$cameraId", it) }.getOrNull()

    fun chooseFpsRange(cameraManager: CameraManager, cameraId: String, desiredFps: Int): Range<Int>? = runCatching {
        val ranges = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        if (ranges.isEmpty()) return@runCatching null
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
            ?: ranges.filter { it.lower == it.upper && it.upper <= desiredFps }
                .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            ?: ranges.filter { it.lower <= desiredFps && it.upper >= desiredFps }
                .minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
            ?: ranges.minWithOrNull(compareBy<Range<Int>> { abs(it.upper - desiredFps) }.thenBy { abs(it.lower - desiredFps) })
    }.getOrNull()

    fun cameraIds(cameraManager: CameraManager): List<String> = try {
        cameraManager.cameraIdList.toList()
    } catch (error: CameraAccessException) {
        V2AppLog.e(TAG, "read cameraIdList failed", error)
        emptyList()
    }

    private fun Size.evenSize(): Size {
        val width = width.coerceAtLeast(2).let { it - (it % 2) }
        val height = height.coerceAtLeast(2).let { it - (it % 2) }
        return Size(width, height)
    }
}
