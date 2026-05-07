package com.kooo.evcam.v2.service.avoidance

import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2AvoidanceSessionActions(
    private val isDisplayPowerOn: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val onHideBlindSpot: () -> Unit,
    private val onHideFisheye: () -> Unit,
    private val onCancelAutoRecording: () -> Unit,
    private val onHideUi: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onRestoreUi: () -> Unit,
    private val onScheduleAutoRecording: () -> Unit,
    private val showToast: (String) -> Unit,
) {
    fun createSnapshot(behaviorMask: Int): V2AvoidanceSnapshot = V2AvoidanceSnapshot(
        behaviorMask = behaviorMask,
        wasRecording = isRecording(),
        wasUiVisible = isUiVisible(),
    )

    fun enter(target: String, config: V2SettingsSnapshot.Avoidance, snapshot: V2AvoidanceSnapshot) {
        V2AppLog.i(
            TAG,
            "enter avoidance target=$target behavior=${config.behaviorLabels()} " +
                "wasRecording=${snapshot.wasRecording} wasUiVisible=${snapshot.wasUiVisible}"
        )

        if (config.hideBlindSpot) {
            V2AppLog.i(TAG, "avoidance hide blind spot overlay")
            onHideBlindSpot()
        }
        onHideFisheye()
        showToast("避让中")
        onCancelAutoRecording()

        if (config.exitForeground) {
            V2AppLog.i(TAG, "avoidance hide UI")
            onHideUi()
        }
        if (config.stopRecording && isRecording()) {
            V2AppLog.i(TAG, "avoidance stop recording")
            onStopRecording()
        }
    }

    fun exit(
        snapshot: V2AvoidanceSnapshot,
        oldTarget: String?,
        generation: Int,
        recordingRestorer: V2AvoidanceRecordingRestorer,
    ) {
        V2AppLog.i(TAG, "exit avoidance target=$oldTarget restoreRecording=${snapshot.wasRecording} restoreUi=${snapshot.wasUiVisible}")
        if (snapshot.wasRecording && isDisplayPowerOn() && !isRecording()) {
            recordingRestorer.restore(generation)
        }
        if (!snapshot.wasRecording && isDisplayPowerOn() && !isRecording()) onScheduleAutoRecording()
        showToast("避让结束")
        if (snapshot.wasUiVisible) {
            onRestoreUi()
        }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
