package com.kooo.evcam.v2.ui.main

import android.content.Intent
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService

internal class V2MainBootAutoRecorder(
    private val mainHandler: Handler,
    private val service: () -> V2CameraForegroundService?,
    private val isBound: () -> Boolean,
    private val recordingUi: V2MainRecordingUiController,
    private val moveTaskToBack: () -> Unit,
) {
    private var autoStartFromBoot = false
    private var silentMode = false
    private var autoRecordingRequested = false

    fun consume(intent: Intent?) {
        val fromBoot = intent?.getBooleanExtra(V2MainActivity.EXTRA_AUTO_START_FROM_BOOT, false) == true
        if (!fromBoot) return
        autoStartFromBoot = true
        silentMode = intent.getBooleanExtra(V2MainActivity.EXTRA_SILENT_MODE, false)
        autoRecordingRequested = true
        intent.removeExtra(V2MainActivity.EXTRA_AUTO_START_FROM_BOOT)
        intent.removeExtra(V2MainActivity.EXTRA_SILENT_MODE)
        V2AppLog.i(TAG, "boot auto start intent consumed silent=$silentMode")
    }

    fun maybeStart() {
        if (!autoStartFromBoot || !autoRecordingRequested || !isBound()) return
        val cameraService = service() ?: return
        autoRecordingRequested = false
        V2AppLog.i(TAG, "schedule boot auto recording alreadyRecording=${cameraService.isNormalRecording()}")
        mainHandler.postDelayed({
            val readyService = service()
            if (readyService == null || !isBound()) {
                V2AppLog.w(TAG, "boot auto recording skipped: service unavailable")
                autoRecordingRequested = true
                return@postDelayed
            }
            if (!readyService.isNormalRecording()) {
                V2AppLog.i(TAG, "boot auto recording start")
                readyService.startRecording()
                recordingUi.updateNormalRecording(readyService.isNormalRecording())
            }
            mainHandler.postDelayed({
                if (silentMode && service()?.isNormalRecording() == true) {
                    V2AppLog.i(TAG, "boot auto recording active, move task to back")
                    moveTaskToBack()
                }
            }, BOOT_MOVE_BACK_DELAY_MS)
        }, BOOT_RECORDING_DELAY_MS)
    }

    private companion object {
        private const val TAG = "V2MainActivity"
        private const val BOOT_RECORDING_DELAY_MS = 3_000L
        private const val BOOT_MOVE_BACK_DELAY_MS = 1_500L
    }
}
