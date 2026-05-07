package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.status.V2UiVisibilityOrchestrator

internal object V2CameraServiceUiVisibilityModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.uiVisibilityOrchestrator = V2UiVisibilityOrchestrator(
            service = graph.service,
            onVisibilityChanged = { graph.readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
    }
}
