package com.kooo.evcam.v2.nativebridge

internal class V2NativeCameraRecordingBridge(private val cameraHandle: Long) {
    val isAvailable: Boolean get() = cameraHandle != 0L

    fun startRecording(
        outputDir: String,
        suffix: String,
        label: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        segmentDurationMs: Long,
        wallClockMs: Long,
        reservedBytes: Long,
        availableBytes: Long,
    ): Boolean = isAvailable && GlesNative.startNativeCameraRecording(
        cameraHandle,
        outputDir,
        suffix,
        label,
        width,
        height,
        bitrate,
        fps,
        segmentDurationMs,
        wallClockMs,
        reservedBytes,
        availableBytes,
    )

    fun stopRecording(timeoutMs: Long, stopWallClockMs: Long): Boolean =
        isAvailable && GlesNative.stopNativeCameraRecording(cameraHandle, timeoutMs, stopWallClockMs)

    fun snapshot(): LongArray = if (isAvailable) GlesNative.snapshotNativeCameraRecording(cameraHandle) else longArrayOf()

    fun lastError(): String = GlesNative.getLastError()
}
