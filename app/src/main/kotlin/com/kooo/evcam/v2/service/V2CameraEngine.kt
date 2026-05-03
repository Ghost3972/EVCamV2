package com.kooo.evcam.v2.service

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.settings.V2SettingsSnapshot
import com.kooo.evcam.v2.recording.V2CompositeRecorder
import com.kooo.evcam.v2.recording.V2RecordingPipelineFactory
import java.io.File

class V2CameraEngine(private val context: Context, private val listener: Listener? = null) {
    interface Listener { fun onStatusChanged(status: String) }

    companion object {
        private const val PREVIEW_MAX_FPS = 30
        private const val RECORDING_PREVIEW_MAX_FPS = 30
        private const val EVENT_SEGMENT_GUARD_MS = 5_000L
        private const val SIDE_LEFT_ROTATION = 270
        private const val SIDE_RIGHT_ROTATION = 90
        private const val DEFAULT_LAYOUT_MODE = 0
    }

    private val specSet = V2CameraSpecProvider.current(context)
    private val specs = specSet.specs
    private val slots = specs.mapIndexed { index, spec -> Slot(index, spec) }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("V2GlesComposite").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private val screenSize = V2CameraDeviceCapabilities.detectScreenSize(context)
    private val recordingConfig = V2RecordingConfigProvider.current(context, screenSize)
    private val recordingSize = recordingConfig.size
    private val recordingFps = recordingConfig.fps
    private val segmentDurationMs = recordingConfig.segmentDurationMs
    private val recordingBitrate = recordingConfig.bitrate
    private val nativeCompositor = V2NativeCompositor.create(recordingSize)
    private val pipelineHandle = nativeCompositor.handle
    private val statusFormatter = V2CameraStatusFormatter(recordingSize, recordingFps)
    private val slotLifecycle = CameraSlotLifecycle()
    private var recording = false
    private var recordingStartedAtMs = 0L
    private var compositor: V2CompositeRecorder? = null
    private var lastPreviewDebugUpdateMs = 0L
    @Volatile private var cameraAccessAllowed = true
    @Volatile private var released = false
    @Volatile private var cameraGeneration = 0
    @Volatile private var previewRenderingEnabled = true

    init {
        V2AppLog.i("V2CameraEngine", "init model=${specSet.modelLabel} specs=${specs.joinToString { "${it.label}:${it.cameraId}/rot${it.rotation}" }} recordingSize=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate fps=$recordingFps segmentMs=$segmentDurationMs codec=H.264 pipelineHandle=$pipelineHandle nativeLoaded=${V2NativeCompositor.isNativeLoaded()}")
        if (!nativeCompositor.isAvailable) V2AppLog.e("V2CameraEngine", "create compositor failed: ${V2NativeCompositor.nativeSummary()} lastError=${V2NativeCompositor.lastError()}")
        configureNativeRuntime(logPrefix = "init")
        if (previewRenderingEnabled) runCatching { nativeCompositor.startPreviewWorker(PREVIEW_MAX_FPS) }
    }

    fun applyFisheyeSettings(fisheye: V2SettingsSnapshot.Fisheye? = null) {
        if (pipelineHandle == 0L) return
        configureNativeRuntime(logPrefix = "fisheye", fisheye = fisheye)
        publishStatus()
    }

    private fun configureNativeRuntime(logPrefix: String, fisheye: V2SettingsSnapshot.Fisheye? = null) {
        if (pipelineHandle == 0L) return
        V2NativeRuntimeConfigurator.configure(
            context = context,
            compositor = nativeCompositor,
            recordingSize = recordingSize,
            recordingFps = recordingFps,
            previewMaxFps = PREVIEW_MAX_FPS,
            sideLeftRotation = SIDE_LEFT_ROTATION,
            sideRightRotation = SIDE_RIGHT_ROTATION,
            layoutMode = DEFAULT_LAYOUT_MODE,
            slots = nativeSlotConfigs(),
            logPrefix = logPrefix,
            fisheye = fisheye,
        )
    }

    private fun nativeSlotConfigs(): List<V2NativeRuntimeConfigurator.SlotConfig> {
        return slots.map { slot -> V2NativeRuntimeConfigurator.SlotConfig(slot.index, slot.spec.label) }
    }

    fun setCameraAccessAllowed(allowed: Boolean) {
        if (cameraAccessAllowed == allowed) {
            if (!allowed) stopRecordingAndReleaseCameras("camera_access_already_disabled")
            return
        }
        cameraAccessAllowed = allowed
        cameraGeneration += 1
        V2AppLog.i("V2CameraEngine", "cameraAccessAllowed=$allowed")
        if (!allowed) {
            stopRecordingAndReleaseCameras("camera_access_disabled")
        } else {
            startCameras()
        }
        publishStatus()
    }

    fun stopRecordingAndReleaseCameras(reason: String) {
        if (cameraAccessAllowed) {
            cameraAccessAllowed = false
            cameraGeneration += 1
            V2AppLog.i("V2CameraEngine", "cameraAccessAllowed=false reason=$reason")
        }
        val openBefore = slots.count { it.nativeCameraHandle != 0L }
        V2AppLog.w("V2CameraEngine", "screen-off release begin reason=$reason recording=$recording openSlots=$openBefore")
        stopRecording()
        stopCameras()
        val openAfter = slots.count { it.nativeCameraHandle != 0L }
        V2AppLog.w("V2CameraEngine", "screen-off release complete reason=$reason recording=$recording openSlots=$openAfter")
        publishStatus()
    }

    fun startCameras() {
        if (released) {
            V2AppLog.w("V2CameraEngine", "startCameras skipped: engine released")
            return
        }
        if (pipelineHandle == 0L) {
            V2AppLog.e("V2CameraEngine", "startCameras skipped: native compositor unavailable")
            return
        }
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "startCameras skipped: screen is off")
            return
        }
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CameraEngine", "startCameras slots=${specs.joinToString { "${it.label}:${it.cameraId}" }}")
        slots.forEach { slot -> slot.ensureInputSurface(); slotLifecycle.openCamera(slot) }
        V2AppLog.perf("V2CameraEngine", "startCameras_schedule", SystemClock.elapsedRealtime() - startedMs, "requestedSlots=${slots.size}")
    }

    fun stopCameras() {
        cameraGeneration += 1
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CameraEngine", "stopCameras openSlots=${slots.count { it.nativeCameraHandle != 0L }}")
        mainHandler.removeCallbacksAndMessages(null)
        slots.forEach { slot ->
            if (slot.previewAttached) {
                runCatching { nativeCompositor.detachPreview(slot.index) }
                slot.previewAttached = false
            }
            slot.close()
        }
        V2AppLog.perf("V2CameraEngine", "stopCameras", SystemClock.elapsedRealtime() - startedMs)
        publishStatus()
    }

    fun attachPreviewSurface(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true) {
        val slot = slot(index) ?: return
        if (released || pipelineHandle == 0L) return
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "attach preview skipped: screen is off ${slot.spec.name}/${slot.spec.cameraId}")
            return
        }

        V2AppLog.d("V2CameraEngine", "attach preview ${slot.spec.name}/${slot.spec.cameraId}")
        if (!nativeCompositor.attachPreview(index, surface, applyFisheye, applyNativeTransform)) {
            slot.previewAttached = false
            V2AppLog.e("V2CameraEngine", "attach preview failed ${slot.spec.name}/${slot.spec.cameraId}: ${nativeCompositor.lastError()}")
            publishStatus()
            return
        }
        slot.previewAttached = true
        publishStatus()
    }

    fun detachPreviewSurface(index: Int) {
        val slot = slot(index) ?: return
        if (pipelineHandle == 0L) return
        slot.previewAttached = false
        V2AppLog.d("V2CameraEngine", "detach preview ${slot.spec.name}/${slot.spec.cameraId}")
        nativeCompositor.detachPreview(index)
        statusFormatter.resetSlot(slot.index, slot.frameSignals, slot.renderedFrames)
        publishStatus()
    }

    fun previewIndexForPosition(position: String): Int? {
        return slots.firstOrNull { it.spec.name == position }?.index
    }

    fun previewDescription(index: Int): String {
        val slot = slot(index) ?: return "unknown"
        return "${slot.spec.name}/${slot.spec.label}/cameraId=${slot.spec.cameraId}/slot=$index"
    }

    fun previewRenderedFrames(index: Int): Long = slot(index)?.renderedFrames ?: 0L

    fun setPreviewRenderingEnabled(enabled: Boolean) {
        if (previewRenderingEnabled == enabled) return
        previewRenderingEnabled = enabled
        V2AppLog.d("V2CameraEngine", "previewRenderingEnabled=$enabled recording=$recording")
        if (enabled) {
            runCatching { nativeCompositor.startPreviewWorker(PREVIEW_MAX_FPS) }
        } else {
            runCatching { nativeCompositor.stopPreviewWorker() }
            renderHandler.post {
                if (previewRenderingEnabled) return@post
                slots.forEach { slot ->
                    slot.lastRenderMs = 0L
                    slot.lastPreviewError = "paused"
                }
                publishStatusIfNeeded()
            }
        }
    }

    fun previewInputSizeLabel(index: Int): String {
        val size = slot(index)?.inputSize ?: recordingSize
        return "${size.width}×${size.height}"
    }

    fun previewInputSize(index: Int): Size? = slot(index)?.inputSize ?: recordingSize

    fun startRecording() {
        startRecordingInternal(fileSuffix = "", activeSegmentDurationMs = segmentDurationMs)
    }

    fun startEventRecording(durationMs: Long) {
        startRecordingInternal(fileSuffix = "_event", activeSegmentDurationMs = durationMs + EVENT_SEGMENT_GUARD_MS)
    }

    fun requestEmergencyClip(durationMs: Long): Boolean {
        return compositor?.requestEmergencyClip(System.currentTimeMillis(), durationMs) == true
    }

    private fun startRecordingInternal(fileSuffix: String, activeSegmentDurationMs: Long) {
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "startRecording skipped: screen is off")
            return
        }
        if (released || pipelineHandle == 0L) {
            V2AppLog.e("V2CameraEngine", "startRecording skipped: native compositor unavailable")
            return
        }
        if (recording) {
            V2AppLog.i("V2CameraEngine", "startRecording ignored: already recording")
            return
        }

        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CameraEngine", "startRecording size=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate fps=$recordingFps segmentMs=$activeSegmentDurationMs suffix=$fileSuffix codec=H.264")
        nativeCompositor.setPreviewMaxFps(RECORDING_PREVIEW_MAX_FPS)
        val next = V2RecordingPipelineFactory.create(
            context = context,
            config = V2RecordingPipelineFactory.Config(
                outputDir = outputDir(),
                nativeHandle = pipelineHandle,
                renderHandler = renderHandler,
                outputWidth = recordingSize.width,
                outputHeight = recordingSize.height,
                videoBitrate = recordingBitrate,
                recordingFps = recordingFps,
                segmentDurationMs = activeSegmentDurationMs,
                fileSuffix = fileSuffix,
            ),
            onFailure = { message -> handleRecorderFailure(message) },
        )
        if (!next.start()) {
            V2AppLog.e("V2CameraEngine", "startRecording failed: compositor start returned false")
            nativeCompositor.setPreviewMaxFps(PREVIEW_MAX_FPS)
            next.stop()
            publishStatus()
            return
        }

        compositor = next
        recording = true
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        V2AppLog.perf("V2CameraEngine", "startRecording", SystemClock.elapsedRealtime() - startedMs, "suffix=$fileSuffix")
        publishStatus()
    }

    fun stopRecordingBlockingForSwitch() {
        stopRecordingInternal(blockForRelease = true)
    }

    fun stopRecording() {
        stopRecordingInternal(blockForRelease = false)
    }

    private fun stopRecordingForRelease() {
        stopRecordingInternal(blockForRelease = true)
    }

    private fun stopRecordingInternal(blockForRelease: Boolean) {
        if (!recording && compositor == null) return
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CameraEngine", "stopRecording blockForRelease=$blockForRelease")
        recording = false
        recordingStartedAtMs = 0L
        val recorder = compositor
        compositor = null
        if (blockForRelease) recorder?.stopBlockingForRelease() else recorder?.stop()
        nativeCompositor.setPreviewMaxFps(PREVIEW_MAX_FPS)
        slots.forEach { if (it.previewAttached) slotLifecycle.restartPreviewAfterRecordingStop(it) }
        val operation = if (blockForRelease) "stopRecording" else "stopRecording_queue"
        V2AppLog.perf("V2CameraEngine", operation, SystemClock.elapsedRealtime() - startedMs, "blockForRelease=$blockForRelease")
        publishStatus()
    }
    fun toggleRecording(): Boolean {
        if (recording) stopRecording() else startRecording()
        return recording
    }
    fun isRecording() = recording
    fun statusText() = status()
    fun healthSnapshot(): V2CameraHealthSnapshot = V2CameraEngineStateMapper.healthSnapshot(
        cameraAccessAllowed = cameraAccessAllowed,
        released = released,
        recording = recording,
        slots = slotStates(),
        recordingMetrics = compositor?.metricsSnapshot(),
    )
    fun release() {
        if (released) return
        V2AppLog.i("V2CameraEngine", "release")
        released = true
        cameraGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        stopRecordingForRelease()
        renderHandler.removeCallbacksAndMessages(null)
        runCatching { nativeCompositor.stopPreviewWorker() }
        slots.forEach { it.close() }
        runCatching { nativeCompositor.release() }
        runCatching { renderThread.quitSafely() }
    }

    private fun slot(index: Int) = slots.getOrNull(index)
    private fun outputDir() = V2StoragePathHelper.outputDir(context)
    private fun slotStates(): List<V2CameraSlotState> {
        val metrics = if (pipelineHandle != 0L && GlesNative.isLoaded) runCatching { GlesNative.getMetricsSnapshot(pipelineHandle) }.getOrDefault(longArrayOf()) else longArrayOf()
        return slots.map { slot -> slot.toState(metrics) }
    }

    private fun Slot.toState(nativeMetrics: LongArray): V2CameraSlotState {
        val nativeBase = 20 + index * 7
        val nativeSignals = nativeMetrics.getOrNull(nativeBase)?.coerceAtLeast(0L) ?: 0L
        val nativeRenders = nativeMetrics.getOrNull(nativeBase + 5)?.coerceAtLeast(0L) ?: 0L
        val nativeDrops = nativeMetrics.getOrNull(nativeBase + 6)?.coerceAtLeast(0L) ?: 0L
        return V2CameraEngineStateMapper.slotState(
            index = index,
            label = spec.label,
            cameraId = spec.cameraId,
            inputSize = inputSize,
            fallbackSize = recordingSize,
            deviceOpen = nativeCameraHandle != 0L,
            sessionOpen = nativeCameraHandle != 0L,
            inputReady = inputSurface != null,
            previewAttached = previewAttached,
            frameSignals = maxOf(frameSignals, nativeSignals),
            renderedFrames = maxOf(renderedFrames, nativeRenders),
            renderFailures = maxOf(renderFailures, nativeDrops),
            lastRenderMs = lastRenderMs,
            lastPreviewError = lastPreviewError,
        )
    }

    private fun handleRecorderFailure(message: String) {
        mainHandler.post {
            if (!recording && compositor == null) return@post
            V2AppLog.e("V2CameraEngine", "recorder failure: $message")
            recording = false
            recordingStartedAtMs = 0L
            compositor = null
            runCatching { nativeCompositor.setPreviewMaxFps(PREVIEW_MAX_FPS) }
            publishStatus()
        }
    }

    private fun status(): String = statusFormatter.status(
        recording = recording,
        recordingStartedAtMs = recordingStartedAtMs,
        metrics = compositor?.metricsSnapshot(),
        slots = V2CameraEngineStateMapper.statusSlots(slotStates()),
    )

    private fun publishStatusIfNeeded() { if (SystemClock.elapsedRealtime() - lastPreviewDebugUpdateMs < 1000L) return; lastPreviewDebugUpdateMs = SystemClock.elapsedRealtime(); publishStatus() }
    private fun publishStatus() { listener?.onStatusChanged(status()) }

    private inner class CameraSlotLifecycle {
        fun restartPreviewAfterRecordingStop(slot: Slot) {
            if (!cameraAccessAllowed) return
            slot.ensureInputSurface()
            if (slot.nativeCameraHandle == 0L) {
                V2AppLog.w("V2CameraEngine", "preview recovery opening camera ${slot.spec.name}/${slot.spec.cameraId}")
                openCamera(slot)
            }
        }

        fun openCamera(slot: Slot) {
            if (released) return
            if (!cameraAccessAllowed) return
            val generation = cameraGeneration
            val cameraId = slot.spec.cameraId
            val availableIds = V2CameraDeviceCapabilities.cameraIds(cameraManager)
            if (!availableIds.contains(cameraId)) {
                V2AppLog.e("V2CameraEngine", "openCamera skipped: cameraId=$cameraId unavailable available=$availableIds spec=${slot.spec.name}")
                return
            }
            if (slot.inputSurface == null) {
                V2AppLog.e("V2CameraEngine", "openCamera skipped: input surface missing ${slot.spec.name}/$cameraId")
                return
            }
            if (!openNativeCamera(slot, cameraId, generation)) {
                slot.lastPreviewError = GlesNative.getLastError()
                V2AppLog.e("V2CameraEngine", "NDK openCamera failed ${slot.spec.name}/$cameraId: ${slot.lastPreviewError}")
                publishStatus()
            }
        }

        private fun openNativeCamera(slot: Slot, cameraId: String, generation: Int): Boolean {
            if (slot.nativeCameraHandle != 0L) return true
            val inputSurface = slot.inputSurface ?: return false
            if (!GlesNative.isLoaded) return false
            val startedMs = SystemClock.elapsedRealtime()
            val handle = runCatching { GlesNative.createNativeCameraPreview(cameraId, inputSurface) }
                .onFailure { V2AppLog.e("V2CameraEngine", "NDK openCamera crashed ${slot.spec.name}/$cameraId", it) }
                .getOrDefault(0L)
            if (released || !cameraAccessAllowed || generation != cameraGeneration || slot.inputSurface == null) {
                if (handle != 0L) runCatching { GlesNative.releaseNativeCameraPreview(handle) }
                return true
            }
            if (handle == 0L) {
                slot.lastPreviewError = GlesNative.getLastError()
                return false
            }
            slot.nativeCameraHandle = handle
            slot.lastPreviewError = "无"
            V2AppLog.perf("V2CameraEngine", "openNativeCamera", SystemClock.elapsedRealtime() - startedMs, "slot=${slot.spec.name}/${slot.spec.cameraId}")
            publishStatus()
            return true
        }
    }

    private inner class Slot(val index: Int, val spec: V2CameraSpec) {
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

        fun close() {
            V2AppLog.d(
                "V2CameraEngine",
                "close slot ${spec.name}/${spec.cameraId} hasNative=${nativeCameraHandle != 0L} hasInput=${inputSurface != null}"
            )
            resetRenderState()
            if (nativeCameraHandle != 0L) {
                runCatching { GlesNative.releaseNativeCameraPreview(nativeCameraHandle) }
                    .onFailure { V2AppLog.w("V2CameraEngine", "release NDK camera failed ${spec.name}/${spec.cameraId}", it) }
                nativeCameraHandle = 0L
            }
            runCatching { nativeCompositor.destroyOesInput(index) }
                .onFailure { V2AppLog.w("V2CameraEngine", "destroy OES input failed ${spec.name}/${spec.cameraId}", it) }
            inputSurface?.release()
            inputSurface = null
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            inputSize = null
        }

        fun resetRenderState() {
            lastRenderMs = 0L
            lastPreviewError = "无"
        }

        fun inputSizeLabel(): String = inputSize?.let { "${it.width}x${it.height}" } ?: "${recordingSize.width}x${recordingSize.height}"
    }

    private fun Slot.ensureInputSurface() {
        if (inputSurface != null) return
        resetRenderState()

        val input = V2CameraInputSurfaceFactory.create(
            cameraManager = cameraManager,
            spec = spec,
            index = index,
            targetSize = recordingSize,
            nativeCompositor = nativeCompositor,
        ) ?: return

        inputSize = input.size
        inputSurfaceTexture = input.texture
        inputSurface = input.surface
    }
}
