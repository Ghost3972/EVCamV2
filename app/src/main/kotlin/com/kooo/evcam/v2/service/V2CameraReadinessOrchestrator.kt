package com.kooo.evcam.v2.service

import com.kooo.evcam.v2.log.V2AppLog

internal class V2CameraReadinessOrchestrator(
    private val engine: V2CameraEngine,
    private val isDisplayPowerOn: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val hasOverlayPreview: () -> Boolean,
    private val restoreMainPreviews: () -> Unit,
    private val resetWatchdog: (reason: String) -> Unit,
    private val syncRecordingStateAndUi: () -> Unit,
) {
    fun ensureReadyAfterPermissions() {
        V2AppLog.i(TAG, "ensureReadyAfterPermissions displayPowerOn=${isDisplayPowerOn()} recording=${engine.isRecording()}")
        if (!isDisplayPowerOn()) return
        engine.setCameraAccessAllowed(true)
        engine.startCameras()
        restoreMainPreviews()
        resetWatchdog("permission_ready")
        syncRecordingStateAndUi()
    }

    fun updatePreviewRenderingEnabled() {
        engine.setPreviewRenderingEnabled(isUiVisible() || hasOverlayPreview())
    }

    fun shouldExpectPreviewRendering(recording: Boolean): Boolean {
        return isUiVisible() || hasOverlayPreview()
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
