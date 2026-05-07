package com.kooo.evcam.v2.service.runtime.module

import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntimeGraph
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.display.V2DisplayPowerOrchestrator

internal object V2CameraServiceDisplayPowerPolicyModule {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.displayPowerOrchestrator = V2DisplayPowerOrchestrator(
            displayPowerController = graph.displayPowerController,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isAutoRecordingEnabled = { graph.startupPolicy().autoStartRecording },
            isRecording = { graph.engine.isRecording() },
            isAvoidanceActive = { graph.avoidanceController.isActive },
            avoidanceTarget = { graph.avoidanceController.activeTarget },
            stopRecordingAndReleaseCameras = { reason -> graph.engine.stopRecordingAndReleaseCameras(reason) },
            setCameraAccessAllowed = { allowed -> graph.engine.setCameraAccessAllowed(allowed) },
            startRecording = { graph.engine.startRecording() },
            publishSnapshot = { reason -> graph.statusReporter.publishSnapshot(reason) },
            dispatchDelayed = { name, delayMs, block ->
                graph.commandQueue.dispatchDelayed(
                    name,
                    delayMs,
                    V2DisplayPowerOrchestrator.DISPLAY_ON_RECORDING_RESTORE_TOKEN,
                    block
                )
            },
            cancelAutoRecording = { graph.autoRecordingController.cancelPending() },
            scheduleAutoRecording = { graph.autoRecordingController.scheduleIfEnabled() },
            clearAvoidance = { reason -> graph.avoidanceController.clear(reason) },
            hideBlindSpot = { graph.blindSpotController.hide() },
            hideFisheye = { graph.fisheyePreviewController.hide() },
            restoreMainPreviews = { graph.previewFacade.restorePreviewSurfaces() },
            saveLog = { V2AppLog.saveToPersistentLog(graph.service) },
        )
    }
}
