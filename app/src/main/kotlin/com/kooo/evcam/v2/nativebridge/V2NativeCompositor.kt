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
    ): Boolean = isAvailable && VulkanNative.setCompositorRuntimeConfig(
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
        isAvailable && VulkanNative.attachPreviewSurfaceWithMode(handle, index, surface, applyFisheye, applyNativeTransform)
    fun detachPreview(index: Int): Boolean = isAvailable && VulkanNative.detachPreviewSurface(handle, index)
    fun setPreviewMaxFps(fps: Int): Boolean = isAvailable && VulkanNative.setPreviewMaxFps(handle, fps)
    fun signalPreviewFrame(index: Int): Long = if (isAvailable) VulkanNative.signalPreviewFrame(handle, index) else -1L
    fun renderScheduledPreview(index: Int): Boolean = isAvailable && VulkanNative.renderScheduledPreview(handle, index)
    fun createOesTexture(index: Int): Int = if (isAvailable) VulkanNative.createOesTexture(handle, index) else 0
    fun createOesInput(index: Int, surfaceTexture: SurfaceTexture): Boolean = isAvailable && VulkanNative.createOesInput(handle, index, surfaceTexture)
    fun destroyOesInput(index: Int): Boolean = isAvailable && VulkanNative.destroyOesInput(handle, index)
    fun release() { if (isAvailable) VulkanNative.releaseCompositor(handle) }
    fun lastError(): String = VulkanNative.getLastError()

    companion object {
        fun create(size: Size): V2NativeCompositor {
            if (!VulkanNative.isLoaded) {
                V2AppLog.e("V2NativeCompositor", "native library unavailable: ${VulkanNative.summaryOrFallback()}")
                return V2NativeCompositor(0L)
            }
            val handle = runCatching { VulkanNative.createCompositor(size.width, size.height) }
                .onFailure { V2AppLog.e("V2NativeCompositor", "create compositor crashed", it) }
                .getOrDefault(0L)
            return V2NativeCompositor(handle)
        }

        fun nativeSummary(): String = VulkanNative.summaryOrFallback()
        fun lastError(): String = VulkanNative.getLastError()
        fun isNativeLoaded(): Boolean = VulkanNative.isLoaded
    }
}
