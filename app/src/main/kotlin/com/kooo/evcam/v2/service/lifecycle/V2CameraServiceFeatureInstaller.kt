package com.kooo.evcam.v2.service.lifecycle

import com.kooo.evcam.v2.service.avoidance.V2AvoidanceController
import com.kooo.evcam.v2.service.avoidance.V2ForegroundAppMonitor
import com.kooo.evcam.v2.service.preview.V2BlindSpotController
import com.kooo.evcam.v2.service.preview.V2FisheyePreviewController
import com.kooo.evcam.v2.service.vhal.V2CustomKeyController

internal object V2CameraServiceFeatureInstaller {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.fisheyePreviewController = V2FisheyePreviewController(
            service = graph.service,
            engine = graph.engine,
            previewSurfaces = graph.previewCoordinator.surfaces,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            showToast = { graph.statusReporter.showToast(it) }
        )
        graph.customKeyController = V2CustomKeyController(
            context = graph.service,
            handler = graph.mainHandler,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            hideUi = { graph.uiVisibilityOrchestrator.hideForAvoidance() },
            showUi = { graph.uiVisibilityOrchestrator.showFromCustomKey() },
        )
        graph.blindSpotController = V2BlindSpotController(
            context = graph.service,
            handler = graph.mainHandler,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            shouldAvoidWindow = { graph.avoidanceController.shouldAvoidBlindSpotWindow() },
            avoidanceTarget = { graph.avoidanceController.activeTarget ?: graph.avoidanceController.currentTarget() },
            previewIndexForSide = { side -> graph.engine.previewIndexForPosition(side) },
            previewDescription = { index -> graph.engine.previewDescription(index) },
            previewInputSize = { index -> graph.engine.previewInputSize(index) },
            attachPreview = { index, surface -> graph.previewFacade.attachBlindSpotPreviewSurface(index, surface) },
            detachPreview = { index -> graph.previewFacade.detachBlindSpotPreviewSurface(index) },
            restoreMainPreview = { index -> graph.previewFacade.restoreMainPreviewSurface(index) },
            hideFisheyePreview = { graph.fisheyePreviewController.hide() },
            hideUi = { graph.uiVisibilityOrchestrator.hideForAvoidance() },
            restoreUi = { graph.uiVisibilityOrchestrator.restoreFromBlindSpot() },
            renderedFrames = { index -> graph.engine.previewRenderedFrames(index) },
            showToast = { message -> graph.statusReporter.showToast(message) },
        )
        graph.avoidanceController = V2AvoidanceController(
            context = graph.service,
            handler = graph.mainHandler,
            foregroundAppMonitor = V2ForegroundAppMonitor(graph.service),
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
