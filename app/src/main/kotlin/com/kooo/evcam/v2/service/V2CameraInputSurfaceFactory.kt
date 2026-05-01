package com.kooo.evcam.v2.service

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor

data class V2CameraInputSurface(
    val texture: SurfaceTexture,
    val surface: Surface,
    val size: Size,
)

object V2CameraInputSurfaceFactory {
    fun create(
        cameraManager: CameraManager,
        spec: V2CameraSpec,
        index: Int,
        targetSize: Size,
        nativeCompositor: V2NativeCompositor,
        callbackHandler: Handler,
        onFrameAvailable: () -> Unit,
    ): V2CameraInputSurface? {
        val textureId = nativeCompositor.createOesTexture(index)
        if (textureId <= 0) {
            V2AppLog.e(TAG, "create OES texture failed ${spec.name}/${spec.cameraId}: ${nativeCompositor.lastError()}")
            return null
        }

        val size = V2CameraDeviceCapabilities.choosePreviewSize(
            cameraManager,
            spec.cameraId,
            targetSize.width,
            targetSize.height,
        ) ?: targetSize.also {
            V2AppLog.w(TAG, "preview size fallback ${spec.name}/${spec.cameraId} ${it.width}x${it.height}")
        }
        V2AppLog.d(TAG, "${spec.label}/${spec.cameraId} OES input size ${size.width}x${size.height} target=${targetSize.width}x${targetSize.height}")

        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener({ onFrameAvailable() }, callbackHandler)
        }
        val surface = Surface(texture)

        if (!nativeCompositor.createOesInput(index, texture)) {
            V2AppLog.e(TAG, "create OES input failed ${spec.name}/${spec.cameraId}: ${nativeCompositor.lastError()}")
            surface.release()
            texture.release()
            return null
        }

        return V2CameraInputSurface(texture, surface, size)
    }

    private const val TAG = "V2CameraEngine"
}
