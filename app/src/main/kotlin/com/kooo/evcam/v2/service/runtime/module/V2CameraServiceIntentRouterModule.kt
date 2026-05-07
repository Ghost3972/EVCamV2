package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.commands.V2CameraServiceActionRouter

internal object V2CameraServiceIntentRouterModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
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
    }
}
