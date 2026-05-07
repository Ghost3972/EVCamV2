package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.camera.V2CameraEngine

internal object V2CameraServiceEngineModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.engine = V2CameraEngine(graph.service, graph.engineListener)
    }
}
