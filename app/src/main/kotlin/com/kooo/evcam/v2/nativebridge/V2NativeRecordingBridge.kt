package com.kooo.evcam.v2.nativebridge

import android.graphics.Bitmap
import android.view.Surface

internal class V2NativeRecordingBridge(private val handle: Long) {
    val isAvailable: Boolean get() = handle != 0L

    fun startSession(fps: Int, segmentDurationMs: Long, wallClockMs: Long): Long =
        VulkanNative.startRecordingSession(handle, fps, segmentDurationMs, wallClockMs)

    fun stopSession(): Boolean = isAvailable && VulkanNative.stopRecordingSession(handle)

    fun attachEncoderSurface(surface: Surface): Boolean =
        isAvailable && VulkanNative.attachEncoderSurface(handle, surface)

    fun detachEncoderSurface(): Boolean = isAvailable && VulkanNative.detachEncoderSurface(handle)

    fun renderCompositor(): Boolean = isAvailable && VulkanNative.renderCompositor(handle)

    fun recordingTickAndRender(wallClockMs: Long): Long =
        if (isAvailable) VulkanNative.recordingTickAndRender(handle, wallClockMs) else -1L

    fun beginNextSegment(): Long = if (isAvailable) VulkanNative.beginNextRecordingSegment(handle) else 0L

    fun completeSegmentSwitch(success: Boolean): Boolean =
        isAvailable && VulkanNative.completeRecordingSegmentSwitch(handle, success)

    fun nextTickDelayMs(): Long = if (isAvailable) VulkanNative.getRecordingNextTickDelayMs(handle) else -1L

    fun updateOverlayBitmap(bitmap: Bitmap, x: Float, y: Float): Boolean =
        isAvailable && VulkanNative.updateRecordingOverlayBitmap(handle, bitmap, x, y)

    fun lastError(): String = VulkanNative.getLastError()
}
