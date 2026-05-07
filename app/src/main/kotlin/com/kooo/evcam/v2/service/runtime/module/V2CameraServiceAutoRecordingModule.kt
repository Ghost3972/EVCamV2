package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.recording.V2AutoRecordingController

internal object V2CameraServiceAutoRecordingModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
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
    }
}
