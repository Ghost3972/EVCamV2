package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.status.V2ServiceStateStore

internal object V2CameraServiceStateModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.stateStore = V2ServiceStateStore(
            initialStatus = graph.engine.statusText(),
            initialNormalRecording = graph.engine.isNormalRecording(),
            initialAnyRecording = graph.engine.isRecording(),
        )
    }
}
