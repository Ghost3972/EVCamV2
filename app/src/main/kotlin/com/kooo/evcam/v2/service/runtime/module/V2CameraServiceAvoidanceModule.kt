package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.avoidance.V2AvoidanceController
import com.kooo.evcam.v2.service.avoidance.V2ForegroundTargetDetector
import com.kooo.evcam.v2.service.avoidance.V2ForegroundAppMonitor

internal object V2CameraServiceAvoidanceModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.avoidanceController = V2AvoidanceController(
            context = graph.service,
            handler = graph.mainHandler,
            targetDetector = V2ForegroundTargetDetector(
                workerHandler = graph.workerHandler,
                callbackHandler = graph.mainHandler,
                foregroundAppMonitor = V2ForegroundAppMonitor(graph.service),
            ),
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isRecording = { graph.engine.isRecording() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            onHideBlindSpot = { graph.blindSpotController.cancelAndHideForAvoidance() },
            onHideFisheye = { graph.fisheyePreviewController.hide() },
            onCancelAutoRecording = { graph.autoRecordingController.cancelPending() },
            onHideUi = { graph.uiVisibilityOrchestrator.hideForAvoidance() },
            onStopRecording = {
                graph.engine.stopRecording()
                graph.statusReporter.publishSnapshot("avoidance_stop_recording")
            },
            onRestoreRecording = {
                graph.engine.startRecording()
                graph.statusReporter.publishSnapshot("avoidance_restore_recording")
            },
            onRestoreUi = { graph.uiVisibilityOrchestrator.restoreFromAvoidance() },
            onScheduleAutoRecording = { graph.autoRecordingController.scheduleIfEnabled() },
            showToast = { graph.statusReporter.showToast(it) }
        )
    }
}
