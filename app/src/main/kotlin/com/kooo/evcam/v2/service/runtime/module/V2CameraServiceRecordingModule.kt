package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.recording.V2RecordingActions
import com.kooo.evcam.v2.service.recording.V2RecordingOrchestrator

internal object V2CameraServiceRecordingModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.recordingOrchestrator = V2RecordingOrchestrator(
            recordingActions = object : V2RecordingActions {
                override fun toggleRecording(): Boolean = graph.engine.toggleRecording()
                override fun startRecording() = graph.engine.startRecording()
                override fun stopRecording() = graph.engine.stopRecording()
            },
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isSystemInteractive = { graph.isSystemInteractive() },
            isNormalRecording = { graph.engine.isNormalRecording() },
            isAvoidanceActive = { graph.avoidanceController.isActive },
            avoidanceTarget = { graph.avoidanceController.activeTarget },
            publishSnapshot = { reason -> graph.statusReporter.publishSnapshot(reason) },
            showToast = { message -> graph.statusReporter.showToast(message) },
        )
    }
}
