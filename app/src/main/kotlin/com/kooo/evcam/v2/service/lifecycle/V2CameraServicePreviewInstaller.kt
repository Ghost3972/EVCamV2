package com.kooo.evcam.v2.service.lifecycle

import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.service.camera.V2CameraReadinessOrchestrator
import com.kooo.evcam.v2.service.preview.V2CameraServicePreviewFacade
import com.kooo.evcam.v2.service.preview.V2PreviewLeaseManager
import com.kooo.evcam.v2.service.preview.V2PreviewSurfaceCoordinator
import com.kooo.evcam.v2.service.status.V2ServiceStatusReporter
import com.kooo.evcam.v2.service.status.V2UiVisibilityOrchestrator

internal object V2CameraServicePreviewInstaller {
    fun install(graph: V2CameraServiceRuntimeGraph) {
        graph.uiVisibilityOrchestrator = V2UiVisibilityOrchestrator(
            service = graph.service,
            onVisibilityChanged = { graph.readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
        graph.statusReporter = V2ServiceStatusReporter(
            service = graph.service,
            statusText = { graph.engine.statusText() },
            isRecording = { graph.engine.isNormalRecording() },
            isAnyRecording = { graph.engine.isRecording() },
            isEmergencyRecordingActive = { graph.recordingOrchestrator.emergencyRecordingActive },
            emergencyRecordingEndsAtWallClockMs = { graph.recordingOrchestrator.emergencyRecordingEndsAtWallClockMs() },
            notifyUiStatus = { status -> graph.uiStatusListener?.invoke(status) },
        )
        graph.previewCoordinator = V2PreviewSurfaceCoordinator(
            slotCount = V2_CAMERA_SLOT_COUNT,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            attachNative = { index, surface, owner ->
                graph.engine.attachPreviewSurface(
                    index = index,
                    surface = surface,
                    applyFisheye = owner != V2PreviewLeaseManager.Owner.BLIND_SPOT,
                    applyNativeTransform = owner != V2PreviewLeaseManager.Owner.MAIN || index < 2,
                )
            },
            detachNative = { index -> graph.engine.detachPreviewSurface(index) },
            onLeasesChanged = { graph.readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
        graph.readinessOrchestrator = V2CameraReadinessOrchestrator(
            engine = graph.engine,
            isDisplayPowerOn = { graph.isDisplayPowerOn() },
            isUiVisible = { graph.uiVisibilityOrchestrator.isVisible },
            hasOverlayPreview = { graph.previewCoordinator.hasOverlayOwner() },
            restoreMainPreviews = { graph.previewFacade.restorePreviewSurfaces() },
            resetWatchdog = { reason -> graph.cameraWatchdog.reset(reason) },
            syncRecordingStateAndUi = { graph.statusReporter.publishSnapshot("readiness") },
        )
        graph.previewFacade = V2CameraServicePreviewFacade(
            engine = graph.engine,
            previewCoordinator = graph.previewCoordinator,
            activeBlindSpotIndex = { graph.blindSpotController.activeCameraIndex },
        )
    }
}
