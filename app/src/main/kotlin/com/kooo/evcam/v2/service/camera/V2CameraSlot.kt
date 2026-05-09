package com.kooo.evcam.v2.service.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.service.V2CameraSpec

internal class V2CameraSlot(
    val index: Int,
    val spec: V2CameraSpec,
    private val nativeCompositor: V2NativeCompositor,
    private val fallbackInputSize: Size,
    private val frameSignalHandler: Handler,
) {
    var nativeCameraHandle: Long = 0L

    var inputSurfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
    var inputSize: Size? = null
    var previewAttached = false

    var frameSignals = 0L
    var renderedFrames = 0L
    var renderFailures = 0L
    var lastRenderMs = 0L
    var lastPreviewError = "无"

    fun ensureInputSurface(cameraManager: CameraManager) {
        if (inputSurface != null) return
        resetRenderState()

        val input = V2CameraInputSurfaceFactory.create(
            cameraManager = cameraManager,
            spec = spec,
            index = index,
            targetSize = fallbackInputSize,
            nativeCompositor = nativeCompositor,
            frameSignalHandler = frameSignalHandler,
        ) ?: return

        inputSize = input.size
        inputSurfaceTexture = input.texture
        inputSurface = input.surface
    }

    fun close() {
        V2AppLog.d(
            TAG,
            "close slot ${spec.name}/${spec.cameraId} hasNative=${nativeCameraHandle != 0L} hasInput=${inputSurface != null}"
        )
        resetRenderState()
        if (nativeCameraHandle != 0L) {
            runCatching { GlesNative.releaseNativeCameraPreview(nativeCameraHandle) }
                .onFailure { V2AppLog.w(TAG, "release NDK camera failed ${spec.name}/${spec.cameraId}", it) }
            nativeCameraHandle = 0L
        }
        runCatching { nativeCompositor.destroyOesInput(index) }
            .onFailure { V2AppLog.w(TAG, "destroy OES input failed ${spec.name}/${spec.cameraId}", it) }
        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.setOnFrameAvailableListener(null)
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null
        inputSize = null
    }

    fun inputSizeLabel(): String {
        return inputSize?.let { "${it.width}x${it.height}" } ?: "${fallbackInputSize.width}x${fallbackInputSize.height}"
    }

    private fun resetRenderState() {
        lastRenderMs = 0L
        lastPreviewError = "无"
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
    }
}
