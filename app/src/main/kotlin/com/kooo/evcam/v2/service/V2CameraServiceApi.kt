package com.kooo.evcam.v2.service

import android.util.Size
import android.view.Surface

interface V2RecordingControlApi {
    fun toggleRecording(): Boolean
    fun startRecording()
    fun stopRecording()
    fun isNormalRecording(): Boolean
}

interface V2UiVisibilityControlApi {
    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)?)
}

interface V2MainPreviewServiceApi {
    fun isPreviewPausedByAvoidance(): Boolean
    fun compositePreviewSizeLabel(): String
    fun compositePreviewRenderedFrames(): Long
    fun compositePreviewFpsMilli(): Long
    fun attachCompositePreviewSurface(surface: Surface)
    fun detachCompositePreviewSurface()
}

interface V2CameraServiceUiApi : V2RecordingControlApi, V2UiVisibilityControlApi, V2MainPreviewServiceApi {
    fun ensureReadyAfterPermissions()
    fun setUiStatusListener(listener: ((String) -> Unit)?)
    fun shutdownFromUi()
}

interface V2BlindSpotPreviewServiceApi {
    fun attachBlindSpotPreviewSurface(index: Int, surface: Surface)
    fun detachBlindSpotPreviewSurface(index: Int)
    fun previewInputSize(index: Int): Size?
    fun previewIndexForPosition(position: String): Int?
    fun previewRenderedFrames(index: Int): Long
}
