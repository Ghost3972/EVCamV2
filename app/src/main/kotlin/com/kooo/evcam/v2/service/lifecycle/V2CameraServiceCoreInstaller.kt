package com.kooo.evcam.v2.service.lifecycle

import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.camera.V2CameraEngine
import com.kooo.evcam.v2.service.commands.V2ServiceCommandQueue
import com.kooo.evcam.v2.service.display.V2DisplayPowerController
import com.kooo.evcam.v2.service.keepalive.V2KeepAliveOrchestrator
import com.kooo.evcam.v2.service.recording.V2AutoRecordingController

internal object V2CameraServiceCoreInstaller {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.commandQueue = V2ServiceCommandQueue(graph.mainHandler)
        graph.displayPowerController = V2DisplayPowerController(
            service = graph.service,
            handler = graph.mainHandler,
            systemInteractive = graph.isSystemInteractive(),
            onDisplayOff = { action ->
                graph.commandQueue.dispatch("displayOff:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOff(action)
                }
            },
            onDisplayOn = { action ->
                graph.commandQueue.dispatch("displayOn:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOn(action)
                }
            },
        )
        V2AppLog.i(
            TAG,
            "onCreate autoRecord=${graph.startupPolicy().autoStartRecording} displayPowerOn=${graph.isDisplayPowerOn()} systemInteractive=${graph.isSystemInteractive()} stableApi=ecarx_display_power"
        )
        graph.engine = V2CameraEngine(graph.service, graph.engineListener)
        graph.autoRecordingController = V2AutoRecordingController(
            service = graph.service,
            handler = graph.mainHandler,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isAutoStartEnabled = { graph.startupPolicy().autoStartRecording && !graph.avoidanceController.isActive },
            isRecording = { graph.engine.isRecording() },
            startRecording = {
                graph.commandQueue.dispatch("autoStartRecording") {
                    graph.recordingOrchestrator.startAutoRecordingIfAllowed()
                }
            },
            showToast = { graph.statusReporter.showToast(it) }
        )
        graph.keepAliveOrchestrator = V2KeepAliveOrchestrator(
            service = graph.service,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            startupPolicy = { graph.startupPolicy() },
            keepAlivePolicy = { graph.keepAlivePolicy() },
            releaseCamerasIfSystemAlreadyNonInteractive = { reason ->
                graph.displayPowerOrchestrator.releaseCamerasIfSystemAlreadyNonInteractive(reason)
            },
        )
    }

    private const val TAG = "V2CameraService"
}
