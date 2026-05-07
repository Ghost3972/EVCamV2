package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.preview.V2FisheyePreviewController

internal object V2CameraServiceFisheyePreviewModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.fisheyePreviewController = V2FisheyePreviewController(
            service = graph.service,
            engine = graph.engine,
            previewSurfaces = graph.previewCoordinator.surfaces,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            showToast = { graph.statusReporter.showToast(it) }
        )
    }
}
