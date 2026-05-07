package com.kooo.evcam.v2.service.status

import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer

internal class V2ServiceStatusReporter(
    service: V2CameraForegroundService,
    private val statusText: () -> String,
    private val isRecording: () -> Boolean,
    private val isAnyRecording: () -> Boolean,
    private val updateState: (String, Boolean, Boolean) -> Unit,
    private val notifyUiStatus: (String) -> Unit,
) {
    private val notificationReporter = V2StatusNotificationReporter(service)
    private val statusBarPluginReporter = V2StatusBarPluginReporter(
        service = service,
        isRecording = isRecording,
        refreshNotification = { status, recording -> notificationReporter.update(status, recording) },
    )
    private val toastDebouncer = V2ToastDebouncer(service)
    private var lastUiStatusText: String? = null

    fun startForeground(text: String) {
        notificationReporter.startForeground(text)
    }

    fun showToast(message: String) {
        toastDebouncer.show(message)
    }

    fun onStatusChanged(status: String) = publishSnapshot("engine", status)

    fun publishSnapshot(reason: String, status: String = statusText(), notifyUi: Boolean = true) {
        val recordingFromStatus = V2RecordingStatusParser.parse(status)
        val recording = recordingFromStatus ?: isRecording()
        val anyRecording = isAnyRecording()
        updateState(status, recording, anyRecording)
        if (notifyUi) updateUiStatusIfChanged(status)
        V2PlaybackCacheMaintainer.setRecordingActive(anyRecording)
        updateStatusBarPluginState(status, recording)
        if (notificationReporter.shouldUpdate(status, recordingFromStatus)) {
            notificationReporter.update(status, recordingFromStatus)
        }
    }

    fun updateUiStatus(status: String = statusText()) {
        notifyUiStatus(status)
        lastUiStatusText = status
    }

    fun updatePlaybackCacheRecordingState() = publishSnapshot("compat_playback", notifyUi = false)

    fun syncRecordingState(status: String = statusText()) = publishSnapshot("compat_sync", status, notifyUi = false)

    fun syncRecordingStateAndUi(status: String = statusText()) = publishSnapshot("compat_sync_ui", status, notifyUi = true)

    fun updateStatusBarPluginState(status: String = statusText(), recordingOverride: Boolean? = null) {
        statusBarPluginReporter.update(status, recordingOverride)
    }

    fun clearStatusBarPluginState() {
        statusBarPluginReporter.clear()
    }

    private fun updateUiStatusIfChanged(status: String) {
        if (status == lastUiStatusText) return
        lastUiStatusText = status
        notifyUiStatus(status)
    }
}
