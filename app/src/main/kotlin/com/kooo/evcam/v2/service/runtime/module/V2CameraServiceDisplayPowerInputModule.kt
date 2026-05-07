package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.display.V2DisplayPowerController

internal object V2CameraServiceDisplayPowerInputModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.displayPowerController = V2DisplayPowerController(
            service = graph.service,
            handler = graph.mainHandler,
            systemInteractive = graph.isSystemInteractive(),
            onDisplayOff = { action ->
                graph.commandQueue.dispatch("displayOff:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOff(action)
                }
            },
            onDisplayOn = { action ->
                graph.commandQueue.dispatch("displayOn:$action") {
                    graph.displayPowerOrchestrator.handleDisplayOn(action)
                }
            },
        )
        V2AppLog.i(
            TAG,
            "onCreate autoRecord=${graph.startupPolicy().autoStartRecording} " +
                "displayPowerOn=${graph.isDisplayPowerOn()} " +
                "systemInteractive=${graph.isSystemInteractive()} stableApi=ecarx_display_power"
        )
    }

    private const val TAG = "V2CameraService"
}
