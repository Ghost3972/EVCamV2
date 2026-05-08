package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.service.preview.V2CameraServicePreviewFacade
import com.kooo.evcam.v2.service.preview.V2PreviewLeaseManager
import com.kooo.evcam.v2.service.preview.V2PreviewSurfaceCoordinator

internal object V2CameraServicePreviewSurfaceModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.previewCoordinator = V2PreviewSurfaceCoordinator(
            slotCount = V2_CAMERA_SLOT_COUNT,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            attachNative = { index, surface, owner ->
                graph.engine.attachPreviewSurface(
                    index = index,
                    surface = surface,
                    applyFisheye = true,
                    applyNativeTransform = owner != V2PreviewLeaseManager.Owner.MAIN || index < 2,
                    useBlindSpotFisheye = owner == V2PreviewLeaseManager.Owner.BLIND_SPOT,
                )
            },
            detachNative = { index -> graph.engine.detachPreviewSurface(index) },
            onLeasesChanged = { graph.readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
        graph.previewFacade = V2CameraServicePreviewFacade(
            engine = graph.engine,
            previewCoordinator = graph.previewCoordinator,
            activeBlindSpotIndex = { graph.blindSpotController.activeCameraIndex },
        )
    }
}
