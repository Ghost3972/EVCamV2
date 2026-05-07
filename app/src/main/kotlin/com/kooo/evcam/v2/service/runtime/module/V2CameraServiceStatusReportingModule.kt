package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.service.status.V2ServiceStatusReporter

internal object V2CameraServiceStatusReportingModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.statusReporter = V2ServiceStatusReporter(
            service = graph.service,
            statusText = { graph.engine.statusText() },
            isRecording = { graph.engine.isNormalRecording() },
            isAnyRecording = { graph.engine.isRecording() },
            updateState = { status, normalRecording, anyRecording ->
                graph.stateStore.update(status, normalRecording, anyRecording)
            },
            notifyUiStatus = { status -> graph.uiStatusListener?.invoke(status) },
        )
    }
}
