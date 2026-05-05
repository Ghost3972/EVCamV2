package com.kooo.evcam.v2.service.preview

import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.camera.V2CameraEngine

internal class V2CameraServicePreviewFacade(
    private val engine: V2CameraEngine,
    private val previewCoordinator: V2PreviewSurfaceCoordinator,
    private val activeBlindSpotIndex: () -> Int,
) {
    private var compositePreviewSurface: Surface? = null

    fun attachCompositePreviewSurface(surface: Surface) {
        compositePreviewSurface = surface
        engine.attachCompositePreviewSurface(surface)
    }

    fun detachCompositePreviewSurface() {
        compositePreviewSurface = null
        engine.detachCompositePreviewSurface()
    }

    fun attachMainPreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachMain(index, surface)
    }

    fun detachMainPreviewSurface(index: Int) {
        previewCoordinator.detachMain(index)
    }

    fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachFisheye(index, surface)
    }

    fun detachFisheyePreviewSurface(index: Int) {
        previewCoordinator.detachFisheye(index)
    }

    fun canShowFisheyePreview(index: Int): Boolean {
        val blindSpotIndex = activeBlindSpotIndex()
        if (blindSpotIndex >= 0 || previewCoordinator.isBlindSpotOwner(index)) {
            V2AppLog.i(TAG, "fisheye preview denied: blind spot active index=$index blindSpotIndex=$blindSpotIndex")
            return false
        }
        return true
    }

    fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachBlindSpot(index, surface)
    }

    fun detachBlindSpotPreviewSurface(index: Int) {
        previewCoordinator.detachBlindSpot(index)
    }

    fun restoreMainPreviewSurface(index: Int) {
        previewCoordinator.restoreMain(index)
    }

    fun restorePreviewSurfaces() {
        compositePreviewSurface?.takeIf { it.isValid }?.let {
            engine.reattachCompositePreviewSurface()
        }
        previewCoordinator.restoreAllMain()
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
