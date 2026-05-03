package com.kooo.evcam.v2.nativebridge

import android.view.Surface

internal class V2NativeRecordingBridge(private val handle: Long) {
    val isAvailable: Boolean get() = handle != 0L

    fun startSession(fps: Int, segmentDurationMs: Long, wallClockMs: Long): Long =
        GlesNative.startRecordingSession(handle, fps, segmentDurationMs, wallClockMs)

    fun stopSession(): Boolean = isAvailable && GlesNative.stopRecordingSession(handle)

    fun setThumbnailPath(path: String): Boolean = isAvailable && GlesNative.setRecordingThumbnailPath(handle, path)

    fun attachEncoderSurface(surface: Surface): Boolean =
        isAvailable && GlesNative.attachEncoderSurface(handle, surface)

    fun detachEncoderSurface(): Boolean = isAvailable && GlesNative.detachEncoderSurface(handle)

    fun startWorker(writerHandle: Long, fps: Int): Boolean =
        isAvailable && GlesNative.startRecordingWorker(handle, writerHandle, fps)

    fun pollWorker(): Long = if (isAvailable) GlesNative.pollRecordingWorker(handle) else -1L

    fun resumeWorker(writerHandle: Long): Boolean =
        isAvailable && GlesNative.resumeRecordingWorker(handle, writerHandle)

    fun stopWorker(timeoutMs: Long): Long = if (isAvailable) GlesNative.stopRecordingWorker(handle, timeoutMs) else -1L

    fun snapshotWorker(): LongArray = if (isAvailable) GlesNative.snapshotRecordingWorker(handle) else longArrayOf()

    fun finalRenderAndDrain(writerHandle: Long, timeoutUs: Long = 0L): Long =
        if (isAvailable) GlesNative.finalRenderAndDrain(handle, writerHandle, timeoutUs) else -1L

    fun beginNextSegment(): Long = if (isAvailable) GlesNative.beginNextRecordingSegment(handle) else 0L

    fun completeSegmentSwitch(success: Boolean): Boolean =
        isAvailable && GlesNative.completeRecordingSegmentSwitch(handle, success)

    fun lastError(): String = GlesNative.getLastError()
}
