package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.keepalive.V2KeepAliveOrchestrator

internal object V2CameraServiceKeepAliveModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
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
}
