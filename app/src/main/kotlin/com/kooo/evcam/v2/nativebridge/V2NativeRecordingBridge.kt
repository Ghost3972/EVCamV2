package com.kooo.evcam.v2.nativebridge

import android.graphics.Bitmap

internal class V2NativeRecordingBridge(private val handle: Long) {
    val isAvailable: Boolean get() = handle != 0L

    fun startManagedRecording(
        outputDir: String,
        suffix: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        segmentDurationMs: Long,
        wallClockMs: Long,
        reservedBytes: Long,
        availableBytes: Long,
    ): Boolean = isAvailable && GlesNative.startManagedRecording(
        handle,
        outputDir,
        suffix,
        width,
        height,
        bitrate,
        fps,
        segmentDurationMs,
        wallClockMs,
        reservedBytes,
        availableBytes,
    )

    fun stopManagedRecording(timeoutMs: Long, stopWallClockMs: Long): Boolean =
        isAvailable && GlesNative.stopManagedRecording(handle, timeoutMs, stopWallClockMs)

    fun snapshotWorker(): LongArray = if (isAvailable) GlesNative.snapshotRecordingWorker(handle) else longArrayOf()

    fun updateWatermarkBitmap(bitmap: Bitmap, x: Int, y: Int): Boolean =
        isAvailable && GlesNative.updateWatermarkBitmap(handle, bitmap, x, y)

    fun clearWatermarkBitmap(): Boolean =
        isAvailable && GlesNative.clearWatermarkBitmap(handle)

    fun metricsSnapshot(): LongArray = if (isAvailable) GlesNative.getMetricsSnapshot(handle) else longArrayOf()

    fun lastError(): String = GlesNative.getLastError()
}
