package com.kooo.evcam.v2.service.lifecycle

import android.content.Intent
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.camera.V2CameraEngine

internal class V2CameraServiceRuntime(
    service: V2CameraForegroundService,
) : V2CameraEngine.Listener {
    private val graph = V2CameraServiceRuntimeGraph(service, this)

    fun start() {
        V2CameraServiceCoreInstaller.install(graph)
        graph.keepAliveOrchestrator.recordCreated()
        V2CameraServicePreviewInstaller.install(graph)
        V2CameraServicePowerInstaller.install(graph)
        V2CameraServiceFeatureInstaller.install(graph)
        V2CameraServiceRoutingInstaller.install(graph)
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
    }

    fun handleTaskRemoved() {
        graph.keepAliveOrchestrator.handleTaskRemoved()
    }

    fun toggleRecording(): Boolean = graph.recordingOrchestrator.toggleRecording()

    fun isRecording(): Boolean = graph.engine.isRecording()

    fun isNormalRecording(): Boolean = graph.engine.isNormalRecording()

    fun statusText(): String = graph.engine.statusText()

    fun isPreviewPausedByAvoidance(): Boolean = false

    fun ensureReadyAfterPermissions() {
        graph.readinessOrchestrator.ensureReadyAfterPermissions()
    }

    fun previewInputSizeLabel(index: Int): String = graph.engine.previewInputSizeLabel(index)

    fun previewInputSize(index: Int): Size? = graph.engine.previewInputSize(index)

    fun compositePreviewSizeLabel(): String = graph.engine.compositePreviewSizeLabel()

    fun attachCompositePreviewSurface(surface: Surface) {
        graph.previewFacade.attachCompositePreviewSurface(surface)
    }

    fun detachCompositePreviewSurface() {
        graph.previewFacade.detachCompositePreviewSurface()
    }

    fun attachPreviewSurface(index: Int, surface: Surface) {
        graph.previewFacade.attachMainPreviewSurface(index, surface)
    }

    fun detachPreviewSurface(index: Int) {
        graph.previewFacade.detachMainPreviewSurface(index)
    }

    fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        graph.previewFacade.attachFisheyePreviewSurface(index, surface)
    }

    fun detachFisheyePreviewSurface(index: Int) {
        graph.previewFacade.detachFisheyePreviewSurface(index)
    }

    fun canShowFisheyePreview(index: Int): Boolean = graph.previewFacade.canShowFisheyePreview(index)

    fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        graph.previewFacade.attachBlindSpotPreviewSurface(index, surface)
    }

    fun detachBlindSpotPreviewSurface(index: Int) {
        graph.previewFacade.detachBlindSpotPreviewSurface(index)
    }

    fun previewIndexForPosition(position: String): Int? = graph.engine.previewIndexForPosition(position)

    fun previewRenderedFrames(index: Int): Long = graph.engine.previewRenderedFrames(index)

    fun compositePreviewRenderedFrames(): Long = graph.engine.compositePreviewRenderedFrames()

    fun compositePreviewFpsMilli(): Long = graph.engine.compositePreviewFpsMilli()

    fun startRecording() {
        graph.recordingOrchestrator.startRecording()
    }

    fun stopRecording() {
        graph.recordingOrchestrator.stopRecording()
    }

    fun startEmergencyRecording(durationMs: Long, onStateChanged: ((Boolean) -> Unit)? = null): Boolean {
        return graph.recordingOrchestrator.startEmergencyRecording(durationMs, onStateChanged)
    }

    fun shutdownFromUi() {
        graph.lifecycleOrchestrator.shutdownFromUi()
    }

    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        graph.uiStatusListener = listener
        listener?.invoke(graph.engine.statusText())
    }

    fun setUiEmergencyRecordingListener(listener: ((Boolean, Long) -> Unit)?) {
        graph.uiEmergencyRecordingListener = listener
        listener?.invoke(
            graph.recordingOrchestrator.emergencyRecordingActive,
            graph.recordingOrchestrator.emergencyRecordingEndsAtMs
        )
    }

    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        graph.uiVisibilityOrchestrator.setVisible(visible, hideListener)
    }

    override fun onStatusChanged(status: String) {
        graph.statusReporter.onStatusChanged(status)
    }
}
