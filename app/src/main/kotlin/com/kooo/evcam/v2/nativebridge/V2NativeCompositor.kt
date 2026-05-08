package com.kooo.evcam.v2.nativebridge

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog

class V2NativeCompositor private constructor(val handle: Long) {
    val isAvailable: Boolean get() = handle != 0L

    fun configureRuntime(
        width: Int,
        height: Int,
        previewFps: Int,
        encoderFps: Int,
        sideLeftRotation: Int,
        sideRightRotation: Int,
        layoutMode: Int,
        fisheyeEnabled: BooleanArray,
        k1: FloatArray,
        k2: FloatArray,
        k3: FloatArray,
        k4: FloatArray,
        zoom: FloatArray,
        centerX: FloatArray,
        centerY: FloatArray,
        fx: FloatArray,
        fy: FloatArray,
        sourceWidth: FloatArray,
        sourceHeight: FloatArray,
        blindSpotFisheyeEnabled: BooleanArray,
        blindSpotK1: FloatArray,
        blindSpotK2: FloatArray,
        blindSpotK3: FloatArray,
        blindSpotK4: FloatArray,
        blindSpotZoom: FloatArray,
        blindSpotCenterX: FloatArray,
        blindSpotCenterY: FloatArray,
        blindSpotFx: FloatArray,
        blindSpotFy: FloatArray,
        blindSpotSourceWidth: FloatArray,
        blindSpotSourceHeight: FloatArray
    ): Boolean = isAvailable && GlesNative.setCompositorRuntimeConfig(
        handle,
        width,
        height,
        previewFps,
        encoderFps,
        sideLeftRotation,
        sideRightRotation,
        layoutMode,
        fisheyeEnabled,
        k1,
        k2,
        k3,
        k4,
        zoom,
        centerX,
        centerY,
        fx,
        fy,
        sourceWidth,
        sourceHeight,
        blindSpotFisheyeEnabled,
        blindSpotK1,
        blindSpotK2,
        blindSpotK3,
        blindSpotK4,
        blindSpotZoom,
        blindSpotCenterX,
        blindSpotCenterY,
        blindSpotFx,
        blindSpotFy,
        blindSpotSourceWidth,
        blindSpotSourceHeight
    )

    fun attachPreview(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true, useBlindSpotFisheye: Boolean = false): Boolean =
        isAvailable && GlesNative.attachPreviewSurfaceWithMode(handle, index, surface, applyFisheye, applyNativeTransform, useBlindSpotFisheye)
    fun attachCompositePreview(surface: Surface): Boolean =
        isAvailable && GlesNative.attachCompositePreviewSurface(handle, surface)
    fun detachCompositePreview(): Boolean = isAvailable && GlesNative.detachCompositePreviewSurface(handle)
    fun detachPreview(index: Int): Boolean = isAvailable && GlesNative.detachPreviewSurface(handle, index)
    fun detachPreviews(indexes: IntArray): Boolean = indexes.isEmpty() || (isAvailable && GlesNative.detachPreviewSurfaces(handle, indexes))
    fun setPreviewMaxFps(fps: Int): Boolean = isAvailable && GlesNative.setPreviewMaxFps(handle, fps)
    fun startPreviewWorker(fps: Int): Boolean = isAvailable && GlesNative.startPreviewWorker(handle, fps)
    fun stopPreviewWorker(timeoutMs: Long = 1_000L): Boolean = isAvailable && GlesNative.stopPreviewWorker(handle, timeoutMs)
    fun createOesTexture(index: Int): Int = if (isAvailable) GlesNative.createOesTexture(handle, index) else 0
    fun createOesInput(index: Int, surfaceTexture: SurfaceTexture): Boolean = isAvailable && GlesNative.createOesInput(handle, index, surfaceTexture)
    fun destroyOesInput(index: Int): Boolean = isAvailable && GlesNative.destroyOesInput(handle, index)
    fun release() { if (isAvailable) GlesNative.releaseCompositor(handle) }
    fun lastError(): String = GlesNative.getLastError()

    companion object {
        fun create(size: Size): V2NativeCompositor {
            if (!GlesNative.isLoaded) {
                V2AppLog.e("V2NativeCompositor", "native library unavailable: ${GlesNative.summaryOrFallback()}")
                return V2NativeCompositor(0L)
            }
            val handle = runCatching { GlesNative.createCompositor(size.width, size.height) }
                .onFailure { V2AppLog.e("V2NativeCompositor", "create compositor crashed", it) }
                .getOrDefault(0L)
            return V2NativeCompositor(handle)
        }

        fun nativeSummary(): String = GlesNative.summaryOrFallback()
        fun lastError(): String = GlesNative.getLastError()
        fun isNativeLoaded(): Boolean = GlesNative.isLoaded
    }
}
