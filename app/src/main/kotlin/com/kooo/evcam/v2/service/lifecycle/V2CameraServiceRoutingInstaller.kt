package com.kooo.evcam.v2.service.lifecycle

import android.app.Service
import com.kooo.evcam.v2.service.commands.V2CameraServiceActionRouter
import com.kooo.evcam.v2.service.recording.V2RecordingOrchestrator
import com.kooo.evcam.v2.service.settings.V2SettingsRuntimeCoordinator
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal object V2CameraServiceRoutingInstaller {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.recordingOrchestrator = V2RecordingOrchestrator(
            handler = graph.mainHandler,
            engine = graph.engine,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isSystemInteractive = { graph.isSystemInteractive() },
            isAvoidanceActive = { graph.avoidanceController.isActive },
            avoidanceTarget = { graph.avoidanceController.activeTarget },
            publishSnapshot = { reason -> graph.statusReporter.publishSnapshot(reason) },
            notifyEmergencyRecordingState = { active, endsAtMs ->
                graph.uiEmergencyRecordingListener?.invoke(active, endsAtMs)
            },
            showToast = { graph.statusReporter.showToast(it) },
        )
        graph.settingsRuntimeCoordinator = V2SettingsRuntimeCoordinator(
            handler = graph.mainHandler,
            loadSnapshot = { V2SettingsRepository.currentSnapshot(graph.service) },
            loadFisheye = { V2SettingsRepository.fisheyeConfig(graph.service) },
            loadAvoidance = { V2SettingsRepository.avoidanceConfig(graph.service) },
            loadBlindSpot = { V2SettingsRepository.blindSpotConfig(graph.service) },
            loadCustomKey = { V2SettingsRepository.customKeyConfig(graph.service) },
            applyFisheye = { fisheye -> graph.engine.applyFisheyeSettings(fisheye) },
            restartBlindSpot = { config -> graph.blindSpotController.restartObserver(config) },
            restartCustomKey = { config -> graph.customKeyController.restart(config) },
            refreshWakeLock = { graph.keepAliveOrchestrator.refreshWakeLock() },
            updateAvoidance = { config -> graph.avoidanceController.updateConfig(config) },
        )
        graph.actionRouter = V2CameraServiceActionRouter(
            scheduleAutoRecording = {
                graph.commandQueue.dispatch("action:autoStartRecording") {
                    graph.autoRecordingController.scheduleIfEnabled()
                }
            },
            settingsChanged = { category ->
                graph.commandQueue.dispatch("action:settingsChanged:$category") {
                    graph.settingsRuntimeCoordinator.onSettingsChanged(category)
                }
            },
            showFisheyePreview = { index ->
                graph.commandQueue.dispatch("action:showFisheye:$index") {
                    graph.fisheyePreviewController.show(index)
                }
            },
            hideFisheyePreview = {
                graph.commandQueue.dispatch("action:hideFisheye") {
                    graph.fisheyePreviewController.hide()
                }
            },
            showBlindSpotPreview = { side ->
                graph.commandQueue.dispatch("action:showBlindSpot:$side") {
                    graph.blindSpotController.showPreview(side)
                }
            },
            hideBlindSpotPreview = {
                graph.commandQueue.dispatch("action:hideBlindSpot") {
                    graph.blindSpotController.hide()
                }
            },
            toggleRecordingFromPlugin = {
                graph.commandQueue.dispatch("action:toggleRecording") {
                    graph.recordingOrchestrator.toggleRecordingFromPlugin()
                }
            },
            startEmergencyFromPlugin = {
                graph.commandQueue.dispatch("action:startEmergency") {
                    graph.recordingOrchestrator.startEmergencyRecordingFromPlugin()
                }
            },
            displayOff = { action ->
                graph.commandQueue.dispatch("action:displayOff:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOff(action)
                }
            },
            displayOn = { action ->
                graph.commandQueue.dispatch("action:displayOn:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOn(action)
                }
            },
        )
        graph.lifecycleOrchestrator = V2CameraServiceLifecycleOrchestrator(
            service = graph.service,
            engine = graph.engine,
            displayPowerController = graph.displayPowerController,
            customKeyController = graph.customKeyController,
            blindSpotController = graph.blindSpotController,
            fisheyePreviewController = graph.fisheyePreviewController,
            avoidanceController = graph.avoidanceController,
            keepAliveOrchestrator = graph.keepAliveOrchestrator,
            statusReporter = graph.statusReporter,
            autoRecordingController = graph.autoRecordingController,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            removeForeground = { graph.service.stopForeground(Service.STOP_FOREGROUND_REMOVE) },
            stopService = { graph.service.stopSelf() },
        )
    }
}
