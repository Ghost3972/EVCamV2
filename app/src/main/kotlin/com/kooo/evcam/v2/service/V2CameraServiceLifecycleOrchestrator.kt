package com.kooo.evcam.v2.service

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer

internal class V2CameraServiceLifecycleOrchestrator(
    private val service: V2CameraForegroundService,
    private val handler: Handler,
    private val engine: V2CameraEngine,
    private val displayPowerController: V2DisplayPowerController,
    private val customKeyController: V2CustomKeyController,
    private val blindSpotController: V2BlindSpotController,
    private val fisheyePreviewController: V2FisheyePreviewController,
    private val avoidanceController: V2AvoidanceController,
    private val cameraWatchdog: V2CameraWatchdog,
    private val keepAliveOrchestrator: V2KeepAliveOrchestrator,
    private val statusReporter: V2ServiceStatusReporter,
    private val autoRecordingController: V2AutoRecordingController,
    private val isDisplayPowerOn: () -> Boolean,
    private val removeForeground: () -> Unit,
    private val stopService: () -> Unit,
) {
    fun startRuntime() {
        statusReporter.startForeground("camera ready")
        V2AppLog.i(TAG, "foreground notification started")
        displayPowerController.register()
        engine.setCameraAccessAllowed(isDisplayPowerOn())
        if (isDisplayPowerOn()) engine.startCameras()
        customKeyController.start()
        blindSpotController.startObserver()
        avoidanceController.start()
        cameraWatchdog.start()
        keepAliveOrchestrator.startInitialChain()
        statusReporter.syncRecordingState()
        V2PlaybackCacheMaintainer.scheduleRefresh(service)
        autoRecordingController.scheduleIfEnabled()
    }

    fun destroyRuntime() {
        V2AppLog.i(TAG, "onDestroy recording=${engine.isRecording()}")
        handler.removeCallbacksAndMessages(null)
        displayPowerController.unregister()
        blindSpotController.stopObserver()
        blindSpotController.hide()
        fisheyePreviewController.hide()
        customKeyController.stop()
        keepAliveOrchestrator.handleDestroy()
        engine.release()
        statusReporter.clearStatusBarPluginState()
        V2AppLog.saveToPersistentLog(service)
    }

    fun shutdownFromUi() {
        V2AppLog.w(TAG, "manual shutdown from UI")
        keepAliveOrchestrator.markManualShutdown()
        handler.removeCallbacksAndMessages(null)
        engine.stopRecording()
        statusReporter.syncRecordingState()
        removeForeground()
        stopService()
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
