package com.kooo.evcam.v2.service.runtime

import android.content.Intent
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.camera.V2CameraEngine
import com.kooo.evcam.v2.service.runtime.module.*

internal class V2CameraServiceRuntime(
    service: V2CameraForegroundService,
) : V2CameraEngine.Listener {
    private val graph = V2CameraServiceRuntimeGraph(service, this)

    fun start() {
        V2CameraServiceCommandQueueModule.install(graph)
        V2CameraServiceDisplayPowerInputModule.install(graph)
        V2CameraServiceEngineModule.install(graph)
        V2CameraServiceStateModule.install(graph)
        V2CameraServiceAutoRecordingModule.install(graph)
        V2CameraServiceKeepAliveModule.install(graph)
        graph.keepAliveOrchestrator.recordCreated()
        V2CameraServiceUiVisibilityModule.install(graph)
        V2CameraServiceStatusReportingModule.install(graph)
        V2CameraServicePreviewSurfaceModule.install(graph)
        V2CameraServiceReadinessModule.install(graph)
        V2CameraServiceDisplayPowerPolicyModule.install(graph)
        V2CameraServiceFisheyePreviewModule.install(graph)
        V2CameraServiceCustomKeyModule.install(graph)
        V2CameraServiceBlindSpotModule.install(graph)
        V2CameraServiceAvoidanceModule.install(graph)
        V2CameraServiceRecordingModule.install(graph)
        V2CameraServiceSettingsModule.install(graph)
        V2CameraServiceIntentRouterModule.install(graph)
        V2CameraServiceLifecycleModule.install(graph)
        graph.lifecycleOrchestrator.startRuntime()
    }

    fun recordStartCommand(action: String?) {
        graph.keepAliveOrchestrator.recordStartCommand(action)
    }

    fun route(intent: Intent?) {
        graph.actionRouter.route(intent)
    }

    fun destroy() {
        graph.lifecycleOrchestrator.destroyRuntime()
        graph.threads.shutdown()
    }

    fun handleTaskRemoved() {
        graph.keepAliveOrchestrator.handleTaskRemoved()
    }

    fun toggleRecording(): Boolean {
        val predicted = predictedRecordingAfterToggle()
        graph.commandQueue.dispatch("ui:toggleRecording") {
            graph.recordingOrchestrator.toggleRecording()
        }
        return predicted
    }

    fun isRecording(): Boolean = graph.stateStore.isRecording

    fun isNormalRecording(): Boolean = graph.stateStore.isNormalRecording

    fun statusText(): String = graph.stateStore.statusText

    fun isPreviewPausedByAvoidance(): Boolean = false

    fun ensureReadyAfterPermissions() {
        graph.commandQueue.dispatch("ui:ensureReadyAfterPermissions") {
            graph.readinessOrchestrator.ensureReadyAfterPermissions()
        }
    }

    fun previewInputSizeLabel(index: Int): String = graph.engine.previewInputSizeLabel(index)

    fun previewInputSize(index: Int): Size? = graph.engine.previewInputSize(index)

    fun compositePreviewSizeLabel(): String = graph.engine.compositePreviewSizeLabel()

    fun attachCompositePreviewSurface(surface: Surface) {
        graph.commandQueue.dispatch("ui:attachCompositePreview") {
            graph.previewFacade.attachCompositePreviewSurface(surface)
        }
    }

    fun detachCompositePreviewSurface() {
        graph.commandQueue.dispatch("ui:detachCompositePreview") {
            graph.previewFacade.detachCompositePreviewSurface()
        }
    }

    fun attachPreviewSurface(index: Int, surface: Surface) {
        graph.commandQueue.dispatch("ui:attachMainPreview:$index") {
            graph.previewFacade.attachMainPreviewSurface(index, surface)
        }
    }

    fun detachPreviewSurface(index: Int) {
        graph.commandQueue.dispatch("ui:detachMainPreview:$index") {
            graph.previewFacade.detachMainPreviewSurface(index)
        }
    }

    fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        graph.commandQueue.dispatch("ui:attachFisheyePreview:$index") {
            graph.previewFacade.attachFisheyePreviewSurface(index, surface)
        }
    }

    fun detachFisheyePreviewSurface(index: Int) {
        graph.commandQueue.dispatch("ui:detachFisheyePreview:$index") {
            graph.previewFacade.detachFisheyePreviewSurface(index)
        }
    }

    fun canShowFisheyePreview(index: Int): Boolean = graph.previewFacade.canShowFisheyePreview(index)

    fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        graph.commandQueue.dispatch("ui:attachBlindSpotPreview:$index") {
            graph.previewFacade.attachBlindSpotPreviewSurface(index, surface)
        }
    }

    fun detachBlindSpotPreviewSurface(index: Int) {
        graph.commandQueue.dispatch("ui:detachBlindSpotPreview:$index") {
            graph.previewFacade.detachBlindSpotPreviewSurface(index)
        }
    }

    fun previewIndexForPosition(position: String): Int? = graph.engine.previewIndexForPosition(position)

    fun previewRenderedFrames(index: Int): Long = graph.engine.previewRenderedFrames(index)

    fun compositePreviewRenderedFrames(): Long = graph.engine.compositePreviewRenderedFrames()

    fun compositePreviewFpsMilli(): Long = graph.engine.compositePreviewFpsMilli()

    fun startRecording() {
        graph.commandQueue.dispatch("ui:startRecording") {
            graph.recordingOrchestrator.startRecording()
        }
    }

    fun stopRecording() {
        graph.commandQueue.dispatch("ui:stopRecording") {
            graph.recordingOrchestrator.stopRecording()
        }
    }

    fun shutdownFromUi() {
        graph.lifecycleOrchestrator.shutdownFromUi()
    }

    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        graph.uiStatusListener = listener
        listener?.invoke(graph.stateStore.statusText)
    }

    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        graph.uiVisibilityOrchestrator.setVisible(visible, hideListener)
    }

    override fun onStatusChanged(status: String) {
        graph.threads.runOnMain {
            graph.statusReporter.onStatusChanged(status)
        }
    }

    private fun predictedRecordingAfterToggle(): Boolean {
        val recording = graph.stateStore.isNormalRecording
        return when {
            recording -> false
            !graph.isDisplayPowerOn() -> false
            graph.avoidanceController.isActive -> false
            else -> true
        }
    }
}
