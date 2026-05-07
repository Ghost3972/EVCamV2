package com.kooo.evcam.v2.service.runtime.module

import android.app.Service
import com.kooo.evcam.v2.service.lifecycle.V2CameraServiceLifecycleOrchestrator
import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph

internal object V2CameraServiceLifecycleModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.lifecycleOrchestrator = V2CameraServiceLifecycleOrchestrator(
            service = graph.service,
            engine = graph.engine,
            displayPowerController = graph.displayPowerController,
            customKeyController = graph.customKeyController,
            blindSpotController = graph.blindSpotController,
            fisheyePreviewController = graph.fisheyePreviewController,
            avoidanceController = graph.avoidanceController,
            keepAliveOrchestrator = graph.keepAliveOrchestrator,
            statusReporter = graph.statusReporter,
            autoRecordingController = graph.autoRecordingController,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            removeForeground = { graph.service.stopForeground(Service.STOP_FOREGROUND_REMOVE) },
            stopService = { graph.service.stopSelf() },
        )
    }
}
