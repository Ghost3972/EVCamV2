package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Size
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.service.V2CameraSpec
import com.kooo.evcam.v2.service.V2CameraSpecSet
import com.kooo.evcam.v2.service.V2RecordingConfig
import com.kooo.evcam.v2.service.recording.V2RecordingConfigProvider

internal class V2CameraEngineEnvironment private constructor(
    val context: Context,
    val specSet: V2CameraSpecSet,
    val specs: List<V2CameraSpec>,
    val cameraManager: CameraManager,
    val mainHandler: Handler,
    val renderThread: HandlerThread,
    val renderHandler: Handler,
    val screenSize: Size,
    val recordingConfig: V2RecordingConfig,
    val recordingSize: Size,
    val compositeOutputSize: Size,
    val recordingFps: Int,
    val previewMaxFps: Int,
    val segmentDurationMs: Long,
    val recordingBitrate: Int,
    val nativeCompositor: V2NativeCompositor,
) {
    val pipelineHandle: Long = nativeCompositor.handle

    fun quitRenderThread() {
        runCatching { renderThread.quitSafely() }
    }

    companion object {
        fun create(context: Context): V2CameraEngineEnvironment {
            val specSet = V2CameraSpecProvider.current(context)
            val screenSize = V2CameraDeviceCapabilities.detectScreenSize(context)
            val recordingConfig = V2RecordingConfigProvider.current(context, screenSize)
            val compositeOutputSize = recordingConfig.outputSize
            val nativeCompositor = V2NativeCompositor.create(compositeOutputSize)
            val renderThread = HandlerThread("V2GlesComposite", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
            return V2CameraEngineEnvironment(
                context = context,
                specSet = specSet,
                specs = specSet.specs,
                cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                mainHandler = Handler(Looper.getMainLooper()),
                renderThread = renderThread,
                renderHandler = Handler(renderThread.looper),
                screenSize = screenSize,
                recordingConfig = recordingConfig,
                recordingSize = recordingConfig.size,
                compositeOutputSize = compositeOutputSize,
                recordingFps = recordingConfig.fps,
                previewMaxFps = recordingConfig.fps.coerceIn(1, 120),
                segmentDurationMs = recordingConfig.segmentDurationMs,
                recordingBitrate = recordingConfig.bitrate,
                nativeCompositor = nativeCompositor,
            )
        }
    }
}
