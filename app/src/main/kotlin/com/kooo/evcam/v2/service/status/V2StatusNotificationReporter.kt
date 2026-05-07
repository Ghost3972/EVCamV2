package com.kooo.evcam.v2.service.status

import com.kooo.evcam.v2.service.V2CameraForegroundService

internal class V2StatusNotificationReporter(service: V2CameraForegroundService) {
    private val notificationHelper = V2CameraNotificationHelper(service)
    private var lastNotificationText: String? = null
    private var lastNotificationMs = 0L
    private var lastNotificationRecording: Boolean? = null

    fun startForeground(text: String) {
        notificationHelper.startForeground(text)
    }

    fun update(status: String, recording: Boolean? = V2RecordingStatusParser.parse(status)) {
        notificationHelper.update(statusBarNotificationText(status))
        lastNotificationText = status
        lastNotificationMs = System.currentTimeMillis()
        lastNotificationRecording = recording
    }

    fun shouldUpdate(status: String, recording: Boolean? = V2RecordingStatusParser.parse(status)): Boolean {
        val now = System.currentTimeMillis()
        val recordingChanged = recording != null && lastNotificationRecording != recording
        val textChanged = lastNotificationText != status
        return recordingChanged ||
            lastNotificationText == null ||
            (textChanged && now - lastNotificationMs >= NOTIFICATION_REFRESH_INTERVAL_MS)
    }

    private fun statusBarNotificationText(status: String): String = status

    private companion object {
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 15_000L
    }
}
