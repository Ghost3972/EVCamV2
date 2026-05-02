package com.kooo.evcam.v2.service

object V2PreviewRenderPolicy {
    fun shouldSkipFrameProcessing(previewRenderingEnabled: Boolean, recording: Boolean): Boolean =
        !previewRenderingEnabled && !recording

    fun desiredCameraFps(
        recording: Boolean,
        recordingFps: Int,
        previewMaxFps: Int,
    ): Int {
        // Keep camera capture at the recording cadence while recording. Native preview
        // rendering is throttled separately so the UI does not steal render-thread time
        // from encoder composition.
        val limit = if (recording) recordingFps else previewMaxFps
        return recordingFps.coerceAtMost(limit).coerceAtLeast(1)
    }
}
