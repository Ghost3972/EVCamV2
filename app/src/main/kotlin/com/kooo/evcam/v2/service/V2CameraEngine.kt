package com.kooo.evcam.v2.service

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.settings.V2RecordingSettings
import com.kooo.evcam.v2.settings.V2VehicleModelSettings
import com.kooo.evcam.v2.recording.V2CompositeRecorder
import com.kooo.evcam.v2.recording.V2RecordingPipelineFactory
import java.io.File

class V2CameraEngine(private val context: Context, private val listener: Listener? = null) {
    interface Listener { fun onStatusChanged(status: String) }

    companion object {
        private const val PREVIEW_MAX_FPS = 30
        private const val RECORDING_PREVIEW_MAX_FPS = 30
        private const val PREVIEW_LOCK_BUSY_RETRY_MS = 8L
        private const val PREVIEW_LOCK_BUSY_RESULT = -2L
        private const val CAMERA_REOPEN_DELAY_MS = 500L
    }

    private val specs = V2CameraSpecProvider.specsForCurrentModel(context)
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
    private var recording = false
    private var recordingStartedAtMs = 0L
    private var compositor: V2CompositeRecorder? = null
    private var lastPreviewDebugUpdateMs = 0L
    private var previewBatchScheduled = false
    private var previewBatchDueMs = 0L
    private val previewBatchRunnable = Runnable { runPreviewRenderBatch() }
    @Volatile private var cameraAccessAllowed = true
    @Volatile private var released = false
    @Volatile private var cameraGeneration = 0
    @Volatile private var previewRenderingEnabled = true

    init {
        V2AppLog.i("V2CameraEngine", "init model=${V2VehicleModelSettings.getModel(context).label} specs=${specs.joinToString { "${it.label}:${it.cameraId}/rot${it.rotation}" }} recordingSize=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate fps=$recordingFps segmentMs=$segmentDurationMs pipelineHandle=$pipelineHandle nativeLoaded=${V2NativeCompositor.isNativeLoaded()}")
        if (!nativeCompositor.isAvailable) V2AppLog.e("V2CameraEngine", "create compositor failed: ${V2NativeCompositor.nativeSummary()} lastError=${V2NativeCompositor.lastError()}")
        configureNativeRuntime(logPrefix = "init")
    }

    fun applyFisheyeSettings() {
        if (pipelineHandle == 0L) return
        configureNativeRuntime(logPrefix = "fisheye")
        publishStatus()
    }

    private fun configureNativeRuntime(logPrefix: String) {
        if (pipelineHandle == 0L) return
        V2NativeRuntimeConfigurator.configure(
            context = context,
            compositor = nativeCompositor,
            recordingSize = recordingSize,
            recordingFps = recordingFps,
            previewMaxFps = PREVIEW_MAX_FPS,
            sideLeftRotation = 270,
            sideRightRotation = 90,
            layoutMode = 0,
            slots = slots.map { V2NativeRuntimeConfigurator.SlotConfig(it.index, it.spec.label) },
            logPrefix = logPrefix,
        )
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
        val openBefore = slots.count { it.device != null || it.session != null }
        V2AppLog.w("V2CameraEngine", "screen-off release begin reason=$reason recording=$recording openSlots=$openBefore")
        stopRecording()
        stopCameras()
        val openAfter = slots.count { it.device != null || it.session != null }
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
        V2AppLog.i("V2CameraEngine", "startCameras specs=${specs.joinToString { "${it.label}:${it.cameraId}" }}")
        slots.forEach { slot -> slot.ensureInputSurface(); openCamera(slot) }
    }

    fun stopCameras() {
        cameraGeneration += 1
        V2AppLog.i("V2CameraEngine", "stopCameras release all camera devices and previews")
        mainHandler.removeCallbacksAndMessages(null)
        slots.forEach { slot ->
            if (slot.previewAttached) {
                runCatching { nativeCompositor.detachPreview(slot.index) }
                slot.previewAttached = false
            }
            slot.close()
        }
        publishStatus()
    }

    fun attachPreviewSurface(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true) {
        val slot = slot(index) ?: return
        if (released || pipelineHandle == 0L) return
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "attach preview skipped: screen is off ${slot.spec.name}/${slot.spec.cameraId}")
            return
        }

        V2AppLog.i("V2CameraEngine", "attach preview ${slot.spec.name}/${slot.spec.cameraId}")
        if (!nativeCompositor.attachPreview(index, surface, applyFisheye, applyNativeTransform)) {
            slot.previewAttached = false
            V2AppLog.e("V2CameraEngine", "attach preview failed ${slot.spec.name}/${slot.spec.cameraId}: ${nativeCompositor.lastError()}")
            publishStatus()
            return
        }
        slot.previewAttached = true
        requestPreviewRenderFromEvent(slot, "surface_attached")
        publishStatus()
    }

    fun detachPreviewSurface(index: Int) {
        val slot = slot(index) ?: return
        if (pipelineHandle == 0L) return
        slot.previewAttached = false
        V2AppLog.i("V2CameraEngine", "detach preview ${slot.spec.name}/${slot.spec.cameraId}")
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
        V2AppLog.i("V2CameraEngine", "previewRenderingEnabled=$enabled recording=$recording")
        if (enabled) {
            slots.forEach { slot ->
                if (slot.previewAttached) requestPreviewRenderFromEvent(slot, "preview_rendering_enabled")
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
        startRecordingInternal(fileSuffix = "_event", activeSegmentDurationMs = durationMs)
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

        val segmentPrecreate = V2RecordingSettings.segmentPrecreateEnabled(context)
        V2AppLog.i("V2CameraEngine", "startRecording size=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate fps=$recordingFps segmentMs=$activeSegmentDurationMs suffix=$fileSuffix precreate=$segmentPrecreate")
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
                segmentPrecreateEnabled = segmentPrecreate,
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
        slots.forEach { requestPreviewRenderFromEvent(it, "recording_started", requirePreviewAttached = false) }
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
        V2AppLog.i("V2CameraEngine", "stopRecording blockForRelease=$blockForRelease")
        recording = false
        recordingStartedAtMs = 0L
        val recorder = compositor
        compositor = null
        if (blockForRelease) recorder?.stopBlockingForRelease() else recorder?.stop()
        nativeCompositor.setPreviewMaxFps(PREVIEW_MAX_FPS)
        slots.forEach { if (it.previewAttached) restartPreviewAfterRecordingStop(it) }
        publishStatus()
    }
    fun toggleRecording(): Boolean {
        if (recording) stopRecording() else startRecording()
        return recording
    }
    fun isRecording() = recording
    fun statusText() = status()
    fun healthSnapshot(): V2CameraHealthSnapshot = V2CameraHealthSnapshot(
        cameraAccessAllowed = cameraAccessAllowed,
        released = released,
        recording = recording,
        slots = slots.map { slot ->
            V2CameraSlotHealth(
                index = slot.index,
                label = slot.spec.label,
                cameraId = slot.spec.cameraId,
                deviceOpen = slot.device != null,
                sessionOpen = slot.session != null,
                inputReady = slot.inputSurface != null,
                previewAttached = slot.previewAttached,
                frameSignals = slot.frameSignals,
                renderedFrames = slot.renderedFrames,
                renderFailures = slot.renderFailures,
                lastRenderMs = slot.lastRenderMs,
                lastError = slot.lastPreviewError
            )
        },
        recordingMetrics = compositor?.metricsSnapshot()
    )
    fun release() {
        if (released) return
        V2AppLog.i("V2CameraEngine", "release")
        released = true
        cameraGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        stopRecordingForRelease()
        renderHandler.removeCallbacksAndMessages(null)
        slots.forEach { it.close() }
        runCatching { nativeCompositor.release() }
        runCatching { renderThread.quitSafely() }
    }

    private fun slot(index: Int) = slots.getOrNull(index)
    private fun outputDir() = V2StoragePathHelper.outputDir(context)

    private fun requestPreviewRender(slot: Slot) {
        if (released || pipelineHandle == 0L) return
        if (!cameraAccessAllowed) return
        slot.frameSignals += 1
        if (V2PreviewRenderPolicy.shouldSkipFrameProcessing(previewRenderingEnabled, recording)) {
            slot.previewRetryPending = false
            slot.lastRenderMs = 0L
            slot.lastPreviewError = "paused"
            publishStatusIfNeeded()
            return
        }
        val delayMs = nativeCompositor.signalPreviewFrame(slot.index)
        if (!slot.previewAttached) {
            publishStatusIfNeeded()
            return
        }
        if (delayMs == PREVIEW_LOCK_BUSY_RESULT) {
            if (!slot.previewRetryPending) {
                slot.previewRetryPending = true
                renderHandler.postDelayed({
                    slot.previewRetryPending = false
                    requestPreviewRender(slot)
                }, PREVIEW_LOCK_BUSY_RETRY_MS)
            }
            return
        }
        if (delayMs < 0L) {
            publishStatusIfNeeded()
            return
        }
        postPreviewRender(slot, delayMs)
    }

    private fun requestPreviewRenderFromEvent(slot: Slot, reason: String, requirePreviewAttached: Boolean = true) {
        val generation = cameraGeneration
        V2AppLog.d("V2CameraEngine", "preview event render reason=$reason ${slot.spec.name}/${slot.spec.cameraId}")
        renderHandler.post {
            if (!released && cameraAccessAllowed && generation == cameraGeneration && (!requirePreviewAttached || slot.previewAttached)) {
                requestPreviewRender(slot)
            }
        }
    }

    private fun postPreviewRender(slot: Slot, delayMs: Long = 0L) {
        val now = SystemClock.elapsedRealtime()
        val dueMs = now + delayMs.coerceAtLeast(0L)
        if (!slot.previewRenderQueued || dueMs < slot.previewRenderDueMs) slot.previewRenderDueMs = dueMs
        slot.previewRenderQueued = true
        if (!previewBatchScheduled || dueMs < previewBatchDueMs) {
            previewBatchScheduled = true
            previewBatchDueMs = dueMs
            renderHandler.removeCallbacks(previewBatchRunnable)
            renderHandler.postDelayed(previewBatchRunnable, (dueMs - now).coerceAtLeast(0L))
        }
    }

    private fun runPreviewRenderBatch() {
        previewBatchScheduled = false
        val now = SystemClock.elapsedRealtime()
        var nextDueMs = Long.MAX_VALUE
        slots.forEach { slot ->
            if (!slot.previewRenderQueued) return@forEach
            if (slot.previewRenderDueMs > now) {
                nextDueMs = minOf(nextDueMs, slot.previewRenderDueMs)
                return@forEach
            }
            slot.previewRenderQueued = false
            renderQueuedPreview(slot)
        }
        if (nextDueMs != Long.MAX_VALUE) {
            previewBatchScheduled = true
            previewBatchDueMs = nextDueMs
            renderHandler.postDelayed(previewBatchRunnable, (nextDueMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
        publishStatusIfNeeded()
    }

    private fun renderQueuedPreview(slot: Slot) {
        if (!slot.previewAttached) return
        slot.lastPreviewPostMs = SystemClock.elapsedRealtime()
        val started = SystemClock.elapsedRealtime()
        val ok = nativeCompositor.renderScheduledPreview(slot.index)
        slot.previewRetryPending = false
        slot.lastRenderMs = SystemClock.elapsedRealtime() - started
        if (ok) {
            slot.renderedFrames += 1
            slot.lastPreviewError = "无"
        } else {
            slot.renderFailures += 1
            slot.lastPreviewError = nativeCompositor.lastError()
            V2AppLog.e("V2CameraEngine", "preview render failed ${slot.spec.name}: ${slot.lastPreviewError}")
        }
    }

    private fun restartPreviewAfterRecordingStop(slot: Slot) {
        if (!cameraAccessAllowed) return
        val generation = cameraGeneration
        slot.ensureInputSurface()
        val device = slot.device
        if (device == null) {
            V2AppLog.w("V2CameraEngine", "preview recovery opening camera ${slot.spec.name}/${slot.spec.cameraId}")
            openCamera(slot)
            return
        }

        val slotHandler = slot.ensureThread()
        slotHandler.post {
            if (released || !cameraAccessAllowed || generation != cameraGeneration) return@post
            V2AppLog.d("V2CameraEngine", "preview recovery restarting session ${slot.spec.name}/${slot.spec.cameraId}")
            runCatching { slot.session?.stopRepeating() }
            runCatching { slot.session?.close() }
            slot.session = null
            slotHandler.postDelayed({
                if (!released && cameraAccessAllowed && generation == cameraGeneration) startPreview(slot)
            }, 120L)
        }
    }

    private fun openCamera(slot: Slot) {
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
        if (slot.device != null) return
        val slotHandler = slot.ensureThread()
        try { V2AppLog.i("V2CameraEngine", "openCamera ${slot.spec.name}/$cameraId"); cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (released || !cameraAccessAllowed || generation != cameraGeneration || slot.inputSurface == null) {
                    V2AppLog.w("V2CameraEngine", "camera opened after release/disable, closing ${slot.spec.name}/${slot.spec.cameraId}")
                    camera.close()
                    return
                }
                V2AppLog.i("V2CameraEngine", "camera opened ${slot.spec.name}/${slot.spec.cameraId}")
                slot.device = camera
                startPreview(slot)
            }
            override fun onDisconnected(camera: CameraDevice) {
                V2AppLog.w("V2CameraEngine", "camera disconnected ${slot.spec.name}/${slot.spec.cameraId}")
                handleCameraDeviceLost(slot, camera, "disconnected")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                V2AppLog.e("V2CameraEngine", "camera error ${slot.spec.name}/${slot.spec.cameraId}: $error")
                handleCameraDeviceLost(slot, camera, "error=$error")
            }
        }, slotHandler) } catch (error: Exception) { V2AppLog.e("V2CameraEngine", "openCamera failed ${slot.spec.name}/$cameraId", error) }
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

    private fun handleCameraDeviceLost(slot: Slot, camera: CameraDevice, reason: String) {
        val generation = cameraGeneration
        runCatching { slot.session?.close() }
        slot.session = null
        runCatching { camera.close() }
        if (slot.device === camera) slot.device = null
        slot.ensureInputSurface()
        slot.handler?.postDelayed({
            if (released || !cameraAccessAllowed || generation != cameraGeneration || slot.device != null) return@postDelayed
            V2AppLog.w("V2CameraEngine", "camera reopen after $reason ${slot.spec.name}/${slot.spec.cameraId}")
            openCamera(slot)
        }, CAMERA_REOPEN_DELAY_MS)
        publishStatus()
    }

    private fun startPreview(slot: Slot) {
        if (released) return
        if (!cameraAccessAllowed) return
        val generation = cameraGeneration
        try {
            val builder = slot.device?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            val inputSurface = slot.inputSurface ?: return
            builder.addTarget(inputSurface)
            val desiredFps = desiredCameraFps()
            V2CameraDeviceCapabilities.chooseFpsRange(cameraManager, slot.spec.cameraId, desiredFps)
                ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            createSession(slot.device ?: return, listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (released || !cameraAccessAllowed || generation != cameraGeneration || slot.device == null || slot.inputSurface == null) {
                        V2AppLog.w("V2CameraEngine", "preview session configured after release/disable, closing ${slot.spec.name}/${slot.spec.cameraId}")
                        runCatching { session.close() }
                        return
                    }
                    slot.session?.close()
                    slot.session = session
                    session.setRepeatingRequest(builder.build(), null, slot.handler)
                    V2AppLog.d("V2CameraEngine", "preview session configured ${slot.spec.name}/${slot.spec.cameraId}")
                    if (slot.previewAttached) requestPreviewRenderFromEvent(slot, "session_configured")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    V2AppLog.e("V2CameraEngine", "preview session configure failed ${slot.spec.name}/${slot.spec.cameraId}")
                    runCatching { session.close() }
                    slot.handler?.postDelayed({
                        if (!released && cameraAccessAllowed && generation == cameraGeneration && slot.device != null && slot.inputSurface != null) startPreview(slot)
                    }, 300L)
                }
            }, slot)
        } catch (t: Exception) {
            V2AppLog.e("V2CameraEngine", "startPreview failed ${slot.spec.name}/${slot.spec.cameraId}", t)
        }
    }

    private fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        slot: Slot
    ) {
        V2CameraSessionFactory.createPreviewSession(device, surfaces, callback, slot.ensureThread())
    }
    private fun desiredCameraFps(): Int = V2PreviewRenderPolicy.desiredCameraFps(
        recording = recording,
        recordingFps = recordingFps,
        previewMaxFps = PREVIEW_MAX_FPS,
        recordingPreviewMaxFps = RECORDING_PREVIEW_MAX_FPS,
    )
    private fun status(): String = statusFormatter.status(
        recording = recording,
        recordingStartedAtMs = recordingStartedAtMs,
        metrics = compositor?.metricsSnapshot(),
        slots = slots.map { slot ->
            V2CameraStatusFormatter.SlotStatus(
                index = slot.index,
                label = slot.spec.label,
                inputSizeLabel = slot.inputSizeLabel(),
                frameSignals = slot.frameSignals,
                renderedFrames = slot.renderedFrames,
                renderFailures = slot.renderFailures,
                lastPreviewError = slot.lastPreviewError,
                lastRenderMs = slot.lastRenderMs
            )
        }
    )

    private fun publishStatusIfNeeded() { if (SystemClock.elapsedRealtime() - lastPreviewDebugUpdateMs < 1000L) return; lastPreviewDebugUpdateMs = SystemClock.elapsedRealtime(); publishStatus() }
    private fun publishStatus() { listener?.onStatusChanged(status()) }

    private inner class Slot(val index: Int, val spec: V2CameraSpec) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        var inputSurfaceTexture: SurfaceTexture? = null
        var inputSurface: Surface? = null
        var inputSize: Size? = null
        var previewAttached = false

        var frameSignals = 0L
        var renderedFrames = 0L
        var renderFailures = 0L
        var lastRenderMs = 0L
        var lastPreviewError = "无"
        var lastPreviewPostMs = 0L
        var previewRetryPending = false
        var previewRenderQueued = false
        var previewRenderDueMs = 0L

        private var thread: HandlerThread? = null
        var handler: Handler? = null
        fun ensureThread(): Handler {
            handler?.let { return it }
            val next = HandlerThread("V2Camera-${spec.name}-${spec.cameraId}").also { it.start() }
            thread = next
            return Handler(next.looper).also { handler = it }
        }

        fun close() {
            V2AppLog.i(
                "V2CameraEngine",
                "close slot ${spec.name}/${spec.cameraId} hasSession=${session != null} hasDevice=${device != null} hasInput=${inputSurface != null}"
            )
            handler?.removeCallbacksAndMessages(null)
            resetRenderState()
            previewRetryPending = false
            session?.close()
            session = null
            device?.close()
            device = null
            runCatching { nativeCompositor.destroyOesInput(index) }
                .onFailure { V2AppLog.w("V2CameraEngine", "destroy OES input failed ${spec.name}/${spec.cameraId}", it) }
            inputSurface?.release()
            inputSurface = null
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            inputSize = null
            thread?.quitSafely()
            runCatching { thread?.join(500L) }
                .onFailure { V2AppLog.w("V2CameraEngine", "join camera thread failed ${spec.name}/${spec.cameraId}", it) }
            thread = null
            handler = null
        }

        fun resetRenderState() {
            lastPreviewPostMs = 0L
            lastRenderMs = 0L
            lastPreviewError = "无"
            previewRenderQueued = false
            previewRenderDueMs = 0L
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
            callbackHandler = ensureThread(),
            onFrameAvailable = { requestPreviewRender(this) },
        ) ?: return

        inputSize = input.size
        inputSurfaceTexture = input.texture
        inputSurface = input.surface
    }
}
