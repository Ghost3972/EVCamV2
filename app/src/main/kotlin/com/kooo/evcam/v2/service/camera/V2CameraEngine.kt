package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.service.V2CameraHealthSnapshot
import com.kooo.evcam.v2.service.recording.V2RecordingConfigProvider
import com.kooo.evcam.v2.settings.V2SettingsSnapshot
import com.kooo.evcam.v2.storage.V2StoragePathHelper

class V2CameraEngine(private val context: Context, private val listener: Listener? = null) {
    interface Listener { fun onStatusChanged(status: String) }

    companion object {
        private const val PREVIEW_MAX_FPS = 30
    }

    private val specSet = V2CameraSpecProvider.current(context)
    private val specs = specSet.specs
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("V2GlesComposite", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private val screenSize = V2CameraDeviceCapabilities.detectScreenSize(context)
    private val recordingConfig = V2RecordingConfigProvider.current(context, screenSize)
    private val recordingSize = recordingConfig.size
    private val compositeOutputSize = recordingConfig.outputSize
    private val recordingFps = recordingConfig.fps
    private val segmentDurationMs = recordingConfig.segmentDurationMs
    private val recordingBitrate = recordingConfig.bitrate
    private val nativeCompositor = V2NativeCompositor.create(compositeOutputSize)
    private val pipelineHandle = nativeCompositor.handle
    private val statusFormatter = V2CameraStatusFormatter(compositeOutputSize, recordingFps)
    private val slots = specs.mapIndexed { index, spec -> V2CameraSlot(index, spec, nativeCompositor, recordingSize) }
    private val nativeRuntimeController = V2CameraNativeRuntimeController(
        context = context,
        nativeCompositor = nativeCompositor,
        pipelineHandle = pipelineHandle,
        recordingSize = compositeOutputSize,
        recordingFps = recordingFps,
        slots = slots,
    )
    private val previewSurfaceController = V2CameraPreviewSurfaceController(
        nativeCompositor = nativeCompositor,
        pipelineHandle = pipelineHandle,
        slots = slots,
        statusFormatter = statusFormatter,
        renderHandler = renderHandler,
        previewMaxFps = PREVIEW_MAX_FPS,
        cameraAccessAllowed = { cameraAccessAllowed },
        released = { released },
        publishStatus = { publishStatus() },
        publishStatusIfNeeded = { publishStatusIfNeeded() },
    )
    private val recordingController = V2CameraRecordingController(
        context = context,
        mainHandler = mainHandler,
        renderHandler = renderHandler,
        nativeCompositor = nativeCompositor,
        pipelineHandle = pipelineHandle,
        outputSize = compositeOutputSize,
        bitrate = recordingBitrate,
        fps = recordingFps,
        segmentDurationMs = segmentDurationMs,
        previewMaxFps = PREVIEW_MAX_FPS,
        cameraAccessAllowed = { cameraAccessAllowed },
        released = { released },
        openCameraCount = { slots.count { it.nativeCameraHandle != 0L } },
        expectedCameraCount = { slots.size },
        outputDir = { outputDir() },
        configureNativeRuntime = { logPrefix -> nativeRuntimeController.configure(logPrefix = logPrefix) },
        restartAttachedPreviews = { restartAttachedPreviewsAfterRecordingStop() },
        startPreviewWorkerIfNeeded = { previewSurfaceController.startPreviewWorkerIfNeeded() },
        requestCameraRecovery = { reason -> recoverCamerasForRecordingStart(reason) },
        publishStatus = { publishStatus() },
    )
    private val statusController = V2CameraEngineStatusController(
        slots = slots,
        pipelineHandle = pipelineHandle,
        fallbackInputSize = recordingSize,
        compositeOutputSize = compositeOutputSize,
        statusFormatter = statusFormatter,
        recordingController = recordingController,
        cameraAccessAllowed = { cameraAccessAllowed },
        released = { released },
    )
    private var lastPreviewDebugUpdateMs = 0L
    @Volatile private var cameraAccessAllowed = true
    @Volatile private var released = false
    @Volatile private var cameraGeneration = 0
    private val slotLifecycle = V2CameraSlotLifecycle(
        cameraManager = cameraManager,
        pipelineHandle = pipelineHandle,
        cameraAccessAllowed = { cameraAccessAllowed },
        released = { released },
        cameraGeneration = { cameraGeneration },
        targetPreviewFps = PREVIEW_MAX_FPS,
        publishStatus = { publishStatus() },
    )
    private val slotSetController = V2CameraSlotSetController(
        cameraManager = cameraManager,
        pipelineHandle = pipelineHandle,
        specs = specs,
        slots = slots,
        slotLifecycle = slotLifecycle,
        previewSurfaceController = previewSurfaceController,
        cameraAccessAllowed = { cameraAccessAllowed },
        released = { released },
        bumpCameraGeneration = { cameraGeneration += 1 },
        publishStatus = { publishStatus() },
    )
    private val accessController = V2CameraAccessController(
        slots = slots,
        recordingController = recordingController,
        cameraAccessAllowed = { cameraAccessAllowed },
        setCameraAccessAllowed = { allowed -> cameraAccessAllowed = allowed },
        bumpCameraGeneration = { cameraGeneration += 1 },
        startCameras = { slotSetController.startCameras() },
        stopRecording = { recordingController.stop() },
        stopCameras = { slotSetController.stopCameras() },
        publishStatus = { publishStatus() },
    )

    init {
        V2AppLog.i("V2CameraEngine", "init model=${specSet.modelLabel} specs=${specs.joinToString { "${it.label}:${it.cameraId}/rot${it.rotation}" }} perCameraSize=${recordingSize.width}x${recordingSize.height} outputSize=${compositeOutputSize.width}x${compositeOutputSize.height} bitrate=$recordingBitrate fps=$recordingFps segmentMs=$segmentDurationMs codec=H.264 pipelineHandle=$pipelineHandle nativeLoaded=${V2NativeCompositor.isNativeLoaded()}")
        if (!nativeCompositor.isAvailable) V2AppLog.e("V2CameraEngine", "create compositor failed: ${V2NativeCompositor.nativeSummary()} lastError=${V2NativeCompositor.lastError()}")
        nativeRuntimeController.configure(logPrefix = "init")
        previewSurfaceController.startPreviewWorkerIfNeeded()
    }

    fun applyFisheyeSettings(fisheye: V2SettingsSnapshot.Fisheye? = null) {
        if (pipelineHandle == 0L) return
        nativeRuntimeController.configure(logPrefix = "fisheye", fisheye = fisheye)
        publishStatus()
    }

    fun setCameraAccessAllowed(allowed: Boolean) {
        accessController.setAllowed(allowed)
    }

    fun stopRecordingAndReleaseCameras(reason: String) {
        accessController.stopRecordingAndReleaseCameras(reason)
    }

    fun startCameras() {
        slotSetController.startCameras()
    }

    fun stopCameras() {
        slotSetController.stopCameras()
    }

    fun attachCompositePreviewSurface(surface: Surface) {
        previewSurfaceController.attachCompositePreviewSurface(surface)
    }

    fun detachCompositePreviewSurface() {
        previewSurfaceController.detachCompositePreviewSurface()
    }

    fun attachPreviewSurface(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true) {
        previewSurfaceController.attachPreviewSurface(index, surface, applyFisheye, applyNativeTransform)
    }

    fun detachPreviewSurface(index: Int) {
        previewSurfaceController.detachPreviewSurface(index)
    }

    fun previewIndexForPosition(position: String): Int? {
        return statusController.previewIndexForPosition(position)
    }

    fun previewDescription(index: Int): String {
        return statusController.previewDescription(index)
    }

    fun previewRenderedFrames(index: Int): Long {
        return statusController.previewRenderedFrames(index)
    }

    fun setPreviewRenderingEnabled(enabled: Boolean) {
        previewSurfaceController.setPreviewRenderingEnabled(enabled)
    }

    fun previewInputSizeLabel(index: Int): String {
        return statusController.previewInputSizeLabel(index)
    }

    fun compositePreviewSizeLabel(): String = statusController.compositePreviewSizeLabel()

    fun previewInputSize(index: Int): Size? = statusController.previewInputSize(index)

    fun startRecording() {
        recordingController.startNormalRecording()
    }

    fun startEventRecording(durationMs: Long) {
        recordingController.startEventRecording(durationMs)
    }

    fun requestEmergencyClip(durationMs: Long): Boolean {
        return recordingController.requestEmergencyClip(durationMs)
    }

    fun stopRecordingBlockingForSwitch() {
        recordingController.stopBlockingForSwitch()
    }

    fun stopRecording() {
        recordingController.stop()
    }

    private fun stopRecordingForRelease() {
        recordingController.stopForRelease()
    }
    fun toggleRecording(): Boolean {
        return recordingController.toggleRecording()
    }
    fun isRecording() = recordingController.isRecording
    fun isNormalRecording() = recordingController.isNormalRecording
    fun statusText() = statusController.statusText()
    fun healthSnapshot(): V2CameraHealthSnapshot = statusController.healthSnapshot()
    fun release() {
        if (released) return
        V2AppLog.i("V2CameraEngine", "release")
        released = true
        cameraGeneration += 1
        stopRecordingForRelease()
        previewSurfaceController.stopPreviewWorkerForRelease()
        slots.forEach { it.close() }
        runCatching { nativeCompositor.release() }
        runCatching { renderThread.quitSafely() }
    }

    private fun outputDir() = V2StoragePathHelper.outputDir(context)

    private fun restartAttachedPreviewsAfterRecordingStop() {
        slots.forEach { if (it.previewAttached) slotLifecycle.restartPreviewAfterRecordingStop(it) }
    }

    private fun recoverCamerasForRecordingStart(reason: String) {
        if (!cameraAccessAllowed || released) return
        V2AppLog.w("V2CameraEngine", "recording start requested camera recovery reason=$reason")
        runCatching {
            slotSetController.stopCameras()
            slotSetController.startCameras()
            previewSurfaceController.startPreviewWorkerIfNeeded()
        }.onFailure { V2AppLog.e("V2CameraEngine", "recording start camera recovery failed reason=$reason", it) }
    }

    private fun publishStatusIfNeeded() { if (SystemClock.elapsedRealtime() - lastPreviewDebugUpdateMs < 1000L) return; lastPreviewDebugUpdateMs = SystemClock.elapsedRealtime(); publishStatus() }
    private fun publishStatus() { listener?.onStatusChanged(statusController.statusText()) }

}
