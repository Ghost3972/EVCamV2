package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.commands.V2ServiceCommandQueue

internal object V2CameraServiceCommandQueueModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.commandQueue = V2ServiceCommandQueue(graph.mainHandler)
    }
}
