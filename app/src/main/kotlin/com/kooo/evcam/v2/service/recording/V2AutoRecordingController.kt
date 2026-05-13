package com.kooo.evcam.v2.service.recording

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

internal class V2AutoRecordingController(
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isAutoStartEnabled: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val startRecording: () -> Unit,
) {
    fun scheduleIfEnabled() {
        cancelPending()
        if (!isAutoStartEnabled()) {
            V2AppLog.i("V2CameraService", "auto recording skipped: disabled")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.i("V2CameraService", "auto recording skipped: display off")
            return
        }
        handler.removeCallbacksAndMessages(AUTO_START_RECORDING_TOKEN)
        if (AUTO_START_RECORDING_DELAY_MS <= 0L) {
            attemptStart()
            return
        }
        V2AppLog.i("V2CameraService", "auto recording scheduled delay=${AUTO_START_RECORDING_DELAY_MS}ms")
        handler.postDelayed({ attemptStart() }, AUTO_START_RECORDING_TOKEN, AUTO_START_RECORDING_DELAY_MS)
    }

    fun cancelPending() {
        handler.removeCallbacksAndMessages(AUTO_START_RECORDING_TOKEN)
    }

    private fun attemptStart() {
        if (!isAutoStartEnabled()) {
            V2AppLog.i("V2CameraService", "auto recording skipped at start time: disabled")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.i("V2CameraService", "auto recording skipped at start time: display off")
            return
        }
        if (!isRecording()) {
            V2AppLog.i("V2CameraService", "auto recording start now")
            startRecording()
        } else {
            V2AppLog.i("V2CameraService", "auto recording skipped: already recording")
        }
    }

    private companion object {
        private const val AUTO_START_RECORDING_DELAY_MS = 3_000L
        private const val AUTO_START_RECORDING_TOKEN = "auto_start_recording"
    }
}
