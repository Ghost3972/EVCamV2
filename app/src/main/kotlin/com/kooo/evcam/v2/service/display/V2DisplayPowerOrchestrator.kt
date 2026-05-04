package com.kooo.evcam.v2.service.display

import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog

internal class V2DisplayPowerOrchestrator(
    private val displayPowerController: V2DisplayPowerController,
    private val isDisplayPowerOn: () -> Boolean,
    private val isAutoRecordingEnabled: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val stopRecordingAndReleaseCameras: (String) -> Unit,
    private val setCameraAccessAllowed: (Boolean) -> Unit,
    private val startRecording: () -> Unit,
    private val publishSnapshot: (String) -> Unit,
    private val dispatchDelayed: (String, Long, () -> Unit) -> Unit,
    private val resetWatchdog: (String) -> Unit,
    private val startWatchdog: () -> Unit,
    private val cancelAutoRecording: () -> Unit,
    private val scheduleAutoRecording: () -> Unit,
    private val clearAvoidance: (String) -> Unit,
    private val hideBlindSpot: () -> Unit,
    private val hideFisheye: () -> Unit,
    private val restoreMainPreviews: () -> Unit,
    private val saveLog: () -> Unit,
) {
    private var resumeRecordingAfterDisplayOn = false

    fun handleDisplayOff(action: String?) {
        val startedMs = SystemClock.elapsedRealtime()
        displayPowerController.markOff(action)
        val autoRecordingEnabled = isAutoRecordingEnabled()
        resumeRecordingAfterDisplayOn = resumeRecordingAfterDisplayOn || isRecording() || autoRecordingEnabled
        V2AppLog.i(TAG, "display off/pre-STR action=$action: stop recording, detach preview, release cameras resumeRecording=$resumeRecordingAfterDisplayOn autoRecording=$autoRecordingEnabled")
        resetWatchdog("display_off")
        cancelAutoRecording()
        clearAvoidance("display off")
        hideBlindSpot()
        hideFisheye()
        pauseCameraForDisplayOff()
        publishSnapshot("display_off")
        V2AppLog.perf(TAG, "displayOff", SystemClock.elapsedRealtime() - startedMs, "action=$action")
        saveLog()
    }

    fun releaseCamerasIfSystemAlreadyNonInteractive(reason: String) {
        if (!isDisplayPowerOn()) {
            stopRecordingAndReleaseCameras("$reason:display_power_off")
            publishSnapshot("release_non_interactive")
            return
        }
        displayPowerController.queryCurrentState(reason)
    }

    fun handleDisplayOn(action: String?) {
        val startedMs = SystemClock.elapsedRealtime()
        if (isDisplayPowerOn()) {
            displayPowerController.markOnIfAlreadyOn(action)
            V2AppLog.i(TAG, "display on ignored: already on action=$action")
            return
        }
        displayPowerController.markOn(action)
        V2AppLog.i(TAG, "display on action=$action: allow cameras and reconnect previews")
        resumeCameraForDisplayOn()
        restoreMainPreviews()
        cancelAutoRecording()
        restoreRecordingAfterDisplayOnIfNeeded()
        resetWatchdog("display_on")
        startWatchdog()
        publishSnapshot("display_on")
        V2AppLog.perf(TAG, "displayOn_schedule", SystemClock.elapsedRealtime() - startedMs, "action=$action")
    }

    private fun restoreRecordingAfterDisplayOnIfNeeded() {
        if (!resumeRecordingAfterDisplayOn) {
            scheduleAutoRecording()
            return
        }
        resumeRecordingAfterDisplayOn = false
        dispatchDelayed("displayOnRestoreRecording", DISPLAY_ON_RECORDING_RESTORE_DELAY_MS) {
            if (!isDisplayPowerOn()) {
                V2AppLog.i(TAG, "display-on recording restore skipped: display off")
                return@dispatchDelayed
            }
            if (isAvoidanceActive()) {
                V2AppLog.i(TAG, "display-on recording restore skipped: avoidance active target=${avoidanceTarget()}")
                return@dispatchDelayed
            }
            if (isRecording()) {
                V2AppLog.i(TAG, "display-on recording restore skipped: already recording")
                return@dispatchDelayed
            }
            val startedMs = SystemClock.elapsedRealtime()
            V2AppLog.i(TAG, "display-on recording restore start")
            startRecording()
            publishSnapshot("display_on_restore_recording")
            V2AppLog.perf(TAG, "displayOnRestoreRecording", SystemClock.elapsedRealtime() - startedMs)
        }
    }

    private fun pauseCameraForDisplayOff() {
        stopRecordingAndReleaseCameras("display_off")
        publishSnapshot("display_off_pause_camera")
    }

    private fun resumeCameraForDisplayOn() {
        setCameraAccessAllowed(true)
    }

    companion object {
        val DISPLAY_ON_RECORDING_RESTORE_TOKEN: Any = "display_on_recording_restore"
        private const val TAG = "V2CameraService"
        private const val DISPLAY_ON_RECORDING_RESTORE_DELAY_MS = 500L
    }
}
