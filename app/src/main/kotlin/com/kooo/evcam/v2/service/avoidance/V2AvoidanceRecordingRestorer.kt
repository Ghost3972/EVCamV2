package com.kooo.evcam.v2.service.avoidance

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

internal class V2AvoidanceRecordingRestorer(
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val currentGeneration: () -> Int,
    private val onRestoreRecording: () -> Unit,
    private val onScheduleAutoRecording: () -> Unit,
) {
    fun restore(generation: Int, attempt: Int = 0) {
        if (generation != currentGeneration() || isAvoidanceActive() || !isDisplayPowerOn() || isRecording()) return
        onRestoreRecording()
        if (isRecording()) return
        if (attempt + 1 >= MAX_RESTORE_RECORDING_ATTEMPTS) {
            V2AppLog.w(TAG, "avoidance restore recording failed after ${attempt + 1} attempts")
            onScheduleAutoRecording()
            return
        }
        handler.postDelayed({
            restore(generation, attempt + 1)
        }, RESTORE_RECORDING_RETRY_DELAY_MS)
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val MAX_RESTORE_RECORDING_ATTEMPTS = 4
        private const val RESTORE_RECORDING_RETRY_DELAY_MS = 500L
    }
}
