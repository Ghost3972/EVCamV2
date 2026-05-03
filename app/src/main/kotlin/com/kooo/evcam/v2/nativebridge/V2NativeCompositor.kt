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
        zoom: FloatArray,
        centerX: FloatArray,
        centerY: FloatArray
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
        zoom,
        centerX,
        centerY
    )

    fun attachPreview(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true): Boolean =
        isAvailable && GlesNative.attachPreviewSurfaceWithMode(handle, index, surface, applyFisheye, applyNativeTransform)
    fun detachPreview(index: Int): Boolean = isAvailable && GlesNative.detachPreviewSurface(handle, index)
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
