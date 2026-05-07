package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.camera.V2CameraReadinessOrchestrator

internal object V2CameraServiceReadinessModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.readinessOrchestrator = V2CameraReadinessOrchestrator(
            engine = graph.engine,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            hasOverlayPreview = { graph.previewCoordinator.hasOverlayOwner() },
            restoreMainPreviews = { graph.previewFacade.restorePreviewSurfaces() },
            syncRecordingStateAndUi = { graph.statusReporter.publishSnapshot("readiness") },
        )
    }
}
