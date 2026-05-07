package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.vhal.V2CustomKeyController

internal object V2CameraServiceCustomKeyModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.customKeyController = V2CustomKeyController(
            context = graph.service,
            handler = graph.mainHandler,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            hideUi = { graph.uiVisibilityOrchestrator.hideForAvoidance() },
            showUi = { graph.uiVisibilityOrchestrator.showFromCustomKey() },
        )
    }
}
