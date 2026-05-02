package com.kooo.evcam.v2.service

import android.widget.Toast
import com.kooo.evcam.v2.plugin.V2StatusBarStateStore
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer

internal class V2ServiceStatusReporter(
    private val service: V2CameraForegroundService,
    private val statusText: () -> String,
    private val isRecording: () -> Boolean,
    private val isEmergencyRecordingActive: () -> Boolean,
    private val emergencyRecordingEndsAtWallClockMs: () -> Long,
    private val notifyUiStatus: (String) -> Unit,
) {
    private val notificationHelper = V2CameraNotificationHelper(service)
    private var lastToastText: String? = null
    private var lastToastMs = 0L
    private var lastNotificationText: String? = null
    private var lastNotificationMs = 0L
    private var lastNotificationRecording: Boolean? = null
    private var lastNotificationEmergency: Boolean? = null
    private var lastUiStatusText: String? = null
    private var lastStatusBarStatus: String? = null
    private var lastStatusBarRecording: Boolean? = null
    private var lastStatusBarEmergency: Boolean? = null
    private var lastStatusBarUpdateMs = 0L

    fun startForeground(text: String) {
        notificationHelper.startForeground(text)
    }

    fun showToast(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastToastText && now - lastToastMs < TOAST_DEBOUNCE_MS) return
        lastToastText = message
        lastToastMs = now
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    fun onStatusChanged(status: String) {
        val recording = parseRecordingState(status)
        updateUiStatusIfChanged(status)
        recording?.let { V2PlaybackCacheMaintainer.setRecordingActive(it) }
        updateStatusBarPluginState(status)
        if (shouldUpdateNotification(status)) {
            updateStatusBarNotification(status, recording)
        }
    }

    fun updateUiStatus(status: String = statusText()) {
        notifyUiStatus(status)
        lastUiStatusText = status
    }

    fun updatePlaybackCacheRecordingState() {
        V2PlaybackCacheMaintainer.setRecordingActive(isRecording())
    }

    fun syncRecordingState(status: String = statusText()) {
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState(status)
    }

    fun syncRecordingStateAndUi(status: String = statusText()) {
        syncRecordingState(status)
        updateUiStatus(status)
    }

    fun updateStatusBarPluginState(status: String = statusText()) {
        val now = android.os.SystemClock.elapsedRealtime()
        val recording = isRecording()
        val emergencyRecordingActive = isEmergencyRecordingActive()
        val stateChanged = recording != lastStatusBarRecording || emergencyRecordingActive != lastStatusBarEmergency
        val textRefreshDue = status != lastStatusBarStatus && now - lastStatusBarUpdateMs >= STATUS_BAR_STATE_INTERVAL_MS
        if (!stateChanged && !textRefreshDue && lastStatusBarStatus != null) return
        lastStatusBarRecording = recording
        lastStatusBarEmergency = emergencyRecordingActive
        lastStatusBarStatus = status
        lastStatusBarUpdateMs = now
        V2StatusBarStateStore.update(service, true, recording, emergencyRecordingActive, status, emergencyRecordingEndsAtWallClockMs())
        if (stateChanged) updateStatusBarNotification(status, recording)
    }

    fun clearStatusBarPluginState() {
        V2StatusBarStateStore.update(service, false, false, false, "")
    }

    private fun updateStatusBarNotification(status: String, recording: Boolean? = parseRecordingState(status)) {
        notificationHelper.update(statusBarNotificationText(status))
        lastNotificationText = status
        lastNotificationMs = System.currentTimeMillis()
        lastNotificationRecording = recording
        lastNotificationEmergency = isEmergencyRecordingActive()
    }

    private fun statusBarNotificationText(status: String): String {
        val emergencyRecordingActive = isEmergencyRecordingActive()
        val emergencyLine = if (emergencyRecordingActive) "emg=ON" else "emg=OFF"
        val endLine = if (emergencyRecordingActive) "emgEnd=${emergencyRecordingEndsAtWallClockMs()}" else "emgEnd=0"
        return if (status.contains("emg=")) status else "$status\n$emergencyLine\n$endLine"
    }

    private fun updateUiStatusIfChanged(status: String) {
        if (status == lastUiStatusText) return
        lastUiStatusText = status
        notifyUiStatus(status)
    }

    private fun shouldUpdateNotification(status: String): Boolean {
        val now = System.currentTimeMillis()
        val recording = parseRecordingState(status)
        val recordingChanged = recording != null && lastNotificationRecording != recording
        val emergencyChanged = lastNotificationEmergency != isEmergencyRecordingActive()
        val textChanged = lastNotificationText != status
        return recordingChanged || emergencyChanged || lastNotificationText == null || textChanged && now - lastNotificationMs >= NOTIFICATION_REFRESH_INTERVAL_MS
    }

    private fun parseRecordingState(status: String): Boolean? {
        val prefix = status.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            prefix.startsWith("rec=ON") -> true
            prefix.startsWith("rec=OFF") -> false
            else -> null
        }
    }

    private companion object {
        private const val TOAST_DEBOUNCE_MS = 5_000L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 15_000L
        private const val STATUS_BAR_STATE_INTERVAL_MS = 15_000L
    }
}
