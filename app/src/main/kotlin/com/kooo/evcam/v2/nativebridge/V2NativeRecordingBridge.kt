package com.kooo.evcam.v2.nativebridge

import android.graphics.Bitmap
import android.view.Surface

internal class V2NativeRecordingBridge(private val handle: Long) {
    val isAvailable: Boolean get() = handle != 0L

    fun startSession(fps: Int, segmentDurationMs: Long, wallClockMs: Long): Long =
        GlesNative.startRecordingSession(handle, fps, segmentDurationMs, wallClockMs)

    fun stopSession(): Boolean = isAvailable && GlesNative.stopRecordingSession(handle)

    fun attachEncoderSurface(surface: Surface): Boolean =
        isAvailable && GlesNative.attachEncoderSurface(handle, surface)

    fun detachEncoderSurface(): Boolean = isAvailable && GlesNative.detachEncoderSurface(handle)

    fun renderCompositor(): Boolean = isAvailable && GlesNative.renderCompositor(handle)

    fun recordingTickAndRender(wallClockMs: Long): Long =
        if (isAvailable) GlesNative.recordingTickAndRender(handle, wallClockMs) else -1L

    fun beginNextSegment(): Long = if (isAvailable) GlesNative.beginNextRecordingSegment(handle) else 0L

    fun completeSegmentSwitch(success: Boolean): Boolean =
        isAvailable && GlesNative.completeRecordingSegmentSwitch(handle, success)

    fun nextTickDelayMs(): Long = if (isAvailable) GlesNative.getRecordingNextTickDelayMs(handle) else -1L

    fun updateOverlayBitmap(bitmap: Bitmap, x: Float, y: Float): Boolean =
        isAvailable && GlesNative.updateRecordingOverlayBitmap(handle, bitmap, x, y)

    fun lastError(): String = GlesNative.getLastError()
}
