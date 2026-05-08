package com.kooo.evcam.v2.service.recording

import com.kooo.evcam.v2.log.V2AppLog

internal class V2RecordingOrchestrator(
    private val recordingActions: V2RecordingActions,
    private val isDisplayPowerOn: () -> Boolean,
    private val isSystemInteractive: () -> Boolean,
    private val isNormalRecording: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val publishSnapshot: (String) -> Unit,
    private val showToast: (String) -> Unit,
) {
    fun toggleRecording(): Boolean {
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "toggleRecording skipped: display off")
            showToast(RECORDING_DISPLAY_OFF_TOAST)
            return false
        }
        val wasRecording = isNormalRecording()
        val result = recordingActions.toggleRecording()
        publishSnapshot("toggle_recording")
        showRecordingTransitionToast(wasRecording, isNormalRecording(), RECORDING_START_FAILED_TOAST)
        V2AppLog.d(TAG, "toggleRecording result=$result")
        return result
    }

    fun startRecording() {
        V2AppLog.i(TAG, "manual startRecording displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        if (isAvoidanceActive()) {
            V2AppLog.w(TAG, "manual startRecording skipped: avoidance active target=${avoidanceTarget()}")
            showToast(RECORDING_START_FAILED_TOAST)
            return
        }
        val wasRecording = isNormalRecording()
        if (isDisplayPowerOn()) {
            recordingActions.startRecording()
            publishSnapshot("manual_start_recording")
            showRecordingTransitionToast(wasRecording, isNormalRecording(), RECORDING_START_FAILED_TOAST)
        } else {
            V2AppLog.w(TAG, "manual startRecording skipped: display off")
            showToast(RECORDING_DISPLAY_OFF_TOAST)
        }
    }

    fun stopRecording() {
        V2AppLog.i(TAG, "manual stopRecording")
        val wasRecording = isNormalRecording()
        recordingActions.stopRecording()
        publishSnapshot("manual_stop_recording")
        showRecordingTransitionToast(wasRecording, isNormalRecording(), RECORDING_ALREADY_STOPPED_TOAST)
    }

    fun startAutoRecordingIfAllowed() {
        if (isAvoidanceActive()) {
            V2AppLog.i(TAG, "auto recording skipped: avoidance active target=${avoidanceTarget()}")
            return
        }
        val wasRecording = isNormalRecording()
        recordingActions.startRecording()
        publishSnapshot("auto_start_recording")
        showRecordingTransitionToast(wasRecording, isNormalRecording())
    }

    fun toggleRecordingFromPlugin() {
        V2AppLog.i(TAG, "plugin toggle recording")
        toggleRecording()
    }

    private fun showRecordingTransitionToast(
        wasRecording: Boolean,
        isRecording: Boolean,
        unchangedMessage: String? = null,
    ) {
        when {
            !wasRecording && isRecording -> showToast(RECORDING_STARTED_TOAST)
            wasRecording && !isRecording -> showToast(RECORDING_STOPPED_TOAST)
            !wasRecording && !isRecording && unchangedMessage != null -> showToast(unchangedMessage)
        }
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val RECORDING_STARTED_TOAST = "开始录制"
        private const val RECORDING_STOPPED_TOAST = "停止录制"
        private const val RECORDING_START_FAILED_TOAST = "录制启动失败"
        private const val RECORDING_ALREADY_STOPPED_TOAST = "已停止录制"
        private const val RECORDING_DISPLAY_OFF_TOAST = "屏幕关闭，无法录制"
    }
}
