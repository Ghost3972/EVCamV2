package com.kooo.evcam.v2.service

object V2PreviewRenderPolicy {
    fun shouldSkipFrameProcessing(previewRenderingEnabled: Boolean, recording: Boolean): Boolean =
        !previewRenderingEnabled && !recording

    fun desiredCameraFps(
        recording: Boolean,
        recordingFps: Int,
        previewMaxFps: Int,
        recordingPreviewMaxFps: Int,
    ): Int {
        val limit = if (recording) recordingPreviewMaxFps else previewMaxFps
        return recordingFps.coerceAtMost(limit).coerceAtLeast(1)
    }
}
