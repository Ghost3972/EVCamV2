package com.kooo.evcam.v2.service.recording

import com.kooo.evcam.v2.log.V2AppLog

internal class V2RecordingOrchestrator(
    private val recordingActions: V2RecordingActions,
    private val isDisplayPowerOn: () -> Boolean,
    private val isSystemInteractive: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val publishSnapshot: (String) -> Unit,
) {
    fun toggleRecording(): Boolean {
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "toggleRecording skipped: display off")
            return false
        }
        val result = recordingActions.toggleRecording()
        publishSnapshot("toggle_recording")
        V2AppLog.d(TAG, "toggleRecording result=$result")
        return result
    }

    fun startRecording() {
        V2AppLog.i(TAG, "manual startRecording displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        if (isAvoidanceActive()) {
            V2AppLog.w(TAG, "manual startRecording skipped: avoidance active target=${avoidanceTarget()}")
            return
        }
        if (isDisplayPowerOn()) {
            recordingActions.startRecording()
            publishSnapshot("manual_start_recording")
        } else {
            V2AppLog.w(TAG, "manual startRecording skipped: display off")
        }
    }

    fun stopRecording() {
        V2AppLog.i(TAG, "manual stopRecording")
        recordingActions.stopRecording()
        publishSnapshot("manual_stop_recording")
    }

    fun startAutoRecordingIfAllowed() {
        if (isAvoidanceActive()) {
            V2AppLog.i(TAG, "auto recording skipped: avoidance active target=${avoidanceTarget()}")
            return
        }
        recordingActions.startRecording()
        publishSnapshot("auto_start_recording")
    }

    fun toggleRecordingFromPlugin() {
        V2AppLog.i(TAG, "plugin toggle recording")
        toggleRecording()
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
