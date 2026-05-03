package com.kooo.evcam.v2.service

import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog

internal class V2WatchdogRestartOrchestrator(
    private val engine: V2CameraEngine,
    private val isDisplayPowerOn: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val restoreMainPreviews: () -> Unit,
    private val publishSnapshot: (String) -> Unit,
    private val dispatchDelayed: (String, Long, () -> Unit) -> Unit,
) {
    fun restartCameras(reason: String) {
        if (!isDisplayPowerOn()) return
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.e(TAG, "watchdog restarting cameras reason=$reason")
        val wasRecording = engine.isRecording()
        runCatching {
            if (wasRecording) {
                engine.stopRecording()
                publishSnapshot("watchdog_stop_recording")
            }
            engine.stopCameras()
            engine.startCameras()
            restoreMainPreviews()
            if (wasRecording && isDisplayPowerOn()) {
                dispatchDelayed("watchdogRestartRecording", RECORDING_RESTART_DELAY_MS) { restartRecordingIfNeeded() }
            }
            publishSnapshot("watchdog_restart")
            V2AppLog.perf(
                TAG,
                "watchdogRestartCameras_schedule",
                SystemClock.elapsedRealtime() - startedMs,
                "reason=$reason wasRecording=$wasRecording recordingRestartDelayed=${wasRecording && isDisplayPowerOn()}"
            )
        }.onFailure { V2AppLog.e(TAG, "watchdog camera restart failed", it) }
    }

    private fun restartRecordingIfNeeded() {
        if (isDisplayPowerOn() && !isAvoidanceActive() && !engine.isRecording()) {
            V2AppLog.w(TAG, "watchdog restarting recording")
            engine.startRecording()
            publishSnapshot("watchdog_restart_recording")
        }
    }

    companion object {
        val RECORDING_RESTART_TOKEN: Any = "watchdog_recording_restart"
        private const val TAG = "V2CameraService"
        private const val RECORDING_RESTART_DELAY_MS = 3_000L
    }
}
