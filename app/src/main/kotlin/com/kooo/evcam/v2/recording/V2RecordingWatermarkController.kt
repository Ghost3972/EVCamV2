package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeRecordingBridge
import com.kooo.evcam.v2.ui.main.V2TimeWatermark

internal class V2RecordingWatermarkController(
    private val context: Context,
    private val native: V2NativeRecordingBridge,
    private val renderHandler: Handler,
    private val isRecording: () -> Boolean,
    private val isStopRequested: () -> Boolean,
) {
    private val watermarkHandler = Handler(Looper.getMainLooper())
    private val watermarkTicker = object : Runnable {
        override fun run() {
            if (!isRecording() || isStopRequested()) return
            updateWatermarkAsync(System.currentTimeMillis())
            watermarkHandler.postDelayed(this, V2TimeWatermark.nextSecondDelayMs())
        }
    }

    fun createBitmap(wallClockMs: Long): Bitmap? {
        return runCatching { V2TimeWatermark.renderBitmap(context, wallClockMs) }
            .onFailure { V2AppLog.e(TAG, "render watermark failed", it) }
            .getOrNull()
    }

    fun uploadInitial(bitmap: Bitmap?) {
        try {
            uploadBitmap(bitmap)
        } finally {
            bitmap?.recycle()
        }
    }

    fun startUpdates() {
        watermarkHandler.removeCallbacks(watermarkTicker)
        watermarkHandler.postDelayed(watermarkTicker, V2TimeWatermark.nextSecondDelayMs())
    }

    fun stopUpdates() {
        watermarkHandler.removeCallbacks(watermarkTicker)
    }

    fun clearNativeWatermark() {
        runCatching { native.clearWatermarkBitmap() }
    }

    private fun updateWatermarkAsync(wallClockMs: Long) {
        val bitmap = createBitmap(wallClockMs) ?: return
        renderHandler.post {
            try {
                if (isRecording() && !isStopRequested()) uploadBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun uploadBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        val uploaded = native.updateWatermarkBitmap(
            bitmap,
            V2TimeWatermark.overlayX(context),
            V2TimeWatermark.overlayY(context),
        )
        if (!uploaded) V2AppLog.w(TAG, "native watermark upload failed: ${native.lastError()}")
    }

    private companion object {
        private const val TAG = "V2CompositeRecorder"
    }
}
