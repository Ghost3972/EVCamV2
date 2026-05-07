package com.kooo.evcam.v2.service.status

import android.os.SystemClock
import com.kooo.evcam.v2.plugin.V2StatusBarStateStore
import com.kooo.evcam.v2.service.V2CameraForegroundService

internal class V2StatusBarPluginReporter(
    private val service: V2CameraForegroundService,
    private val isRecording: () -> Boolean,
    private val refreshNotification: (String, Boolean?) -> Unit,
) {
    private var lastStatus: String? = null
    private var lastRecording: Boolean? = null
    private var lastUpdateMs = 0L

    fun update(status: String, recordingOverride: Boolean? = null) {
        val now = SystemClock.elapsedRealtime()
        val recording = recordingOverride ?: isRecording()
        val stateChanged = recording != lastRecording
        val textRefreshDue = status != lastStatus && now - lastUpdateMs >= STATUS_BAR_STATE_INTERVAL_MS
        if (!stateChanged && !textRefreshDue && lastStatus != null) return

        lastRecording = recording
        lastStatus = status
        lastUpdateMs = now
        V2StatusBarStateStore.update(
            service,
            true,
            recording,
            status,
        )
        if (stateChanged) refreshNotification(status, recording)
    }

    fun clear() {
        V2StatusBarStateStore.update(service, false, false, "")
    }

    private companion object {
        private const val STATUS_BAR_STATE_INTERVAL_MS = 15_000L
    }
}
