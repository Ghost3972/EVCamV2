package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.recording.RecordingMetrics
import com.kooo.evcam.v2.recording.V2RecordingPipeline
import com.kooo.evcam.v2.recording.V2RecordingPipelineFactory
import java.io.File

internal class V2CameraRecordingController(
    private val context: Context,
    private val mainHandler: Handler,
    private val renderHandler: Handler,
    private val nativeCompositor: V2NativeCompositor,
    private val pipelineHandle: Long,
    private val outputSize: Size,
    private val bitrate: Int,
    private val fps: Int,
    private val segmentDurationMs: Long,
    private val previewMaxFps: Int,
    private val cameraAccessAllowed: () -> Boolean,
    private val released: () -> Boolean,
    private val openCameraCount: () -> Int,
    private val expectedCameraCount: () -> Int,
    private val outputDir: () -> File,
    private val configureNativeRuntime: (logPrefix: String) -> Unit,
    private val restartAttachedPreviews: () -> Unit,
    private val startPreviewWorkerIfNeeded: () -> Unit,
    private val publishStatus: () -> Unit,
) {
    private var pipeline: V2RecordingPipeline? = null
    private var recording = false
    private var normalRecording = false
    private var recordingStartedAtMs = 0L

    val isRecording: Boolean get() = recording
    val isNormalRecording: Boolean get() = normalRecording
    val startedAtMs: Long get() = recordingStartedAtMs

    fun startNormalRecording() {
        startRecordingInternal(fileSuffix = "", activeSegmentDurationMs = segmentDurationMs)
    }

    fun startEventRecording(durationMs: Long) {
        startRecordingInternal(fileSuffix = "_event", activeSegmentDurationMs = durationMs + EVENT_SEGMENT_GUARD_MS)
    }

    fun requestEmergencyClip(durationMs: Long): Boolean {
        return pipeline?.requestEmergencyClip(System.currentTimeMillis(), durationMs) == true
    }

    fun stopBlockingForSwitch() {
        stopRecordingInternal(blockForRelease = true)
    }

    fun stop() {
        stopRecordingInternal(blockForRelease = false)
    }

    fun stopForRelease() {
        stopRecordingInternal(blockForRelease = true)
    }

    fun toggleRecording(): Boolean {
        when {
            normalRecording -> stop()
            recording -> V2AppLog.i(TAG, "toggleRecording ignored: event recording active")
            else -> startNormalRecording()
        }
        return normalRecording
    }

    fun metricsSnapshot(): RecordingMetrics? = pipeline?.metricsSnapshot()

    private fun startRecordingInternal(fileSuffix: String, activeSegmentDurationMs: Long) {
        val normalMode = fileSuffix.isEmpty()
        if (!cameraAccessAllowed()) {
            V2AppLog.w(TAG, "startRecording skipped: screen is off")
            return
        }
        if (released() || pipelineHandle == 0L) {
            V2AppLog.e(TAG, "startRecording skipped: native compositor unavailable")
            return
        }
        if (recording) {
            V2AppLog.i(TAG, "startRecording ignored: already recording")
            return
        }

        val startedMs = SystemClock.elapsedRealtime()
        val openCount = openCameraCount()
        val expectedCount = expectedCameraCount()
        if (openCount < expectedCount) {
            V2AppLog.e(TAG, "startRecording skipped: composite needs all cameras open open=$openCount expected=$expectedCount")
            publishStatus()
            return
        }

        V2AppLog.i(TAG, "startRecording mode=composite outputSize=${outputSize.width}x${outputSize.height} cameras=$openCount bitrate=$bitrate fps=$fps segmentMs=$activeSegmentDurationMs suffix=$fileSuffix codec=H.264")
        configureNativeRuntime("recording-start")
        val next = V2RecordingPipelineFactory.create(
            context = context,
            config = V2RecordingPipelineFactory.Config(
                outputDir = outputDir(),
                nativeHandle = pipelineHandle,
                renderHandler = renderHandler,
                outputWidth = outputSize.width,
                outputHeight = outputSize.height,
                videoBitrate = bitrate,
                recordingFps = fps,
                segmentDurationMs = activeSegmentDurationMs,
                fileSuffix = fileSuffix,
                cameraTargets = emptyList(),
            ),
            onFailure = { message -> handleRecorderFailure(message) },
        )
        if (!next.start()) {
            V2AppLog.e(TAG, "startRecording failed: compositor start returned false")
            configureNativeRuntime("recording-start-failed")
            next.stop()
            publishStatus()
            return
        }

        pipeline = next
        recording = true
        normalRecording = normalMode
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        V2AppLog.perf(TAG, "startRecording", SystemClock.elapsedRealtime() - startedMs, "suffix=$fileSuffix")
        publishStatus()
    }

    private fun stopRecordingInternal(blockForRelease: Boolean) {
        if (!recording && pipeline == null) return
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i(TAG, "stopRecording blockForRelease=$blockForRelease")
        recording = false
        normalRecording = false
        recordingStartedAtMs = 0L
        val recorder = pipeline
        pipeline = null
        if (blockForRelease) recorder?.stopBlockingForRelease() else recorder?.stop()
        configureNativeRuntime("recording-stop")
        restartAttachedPreviews()
        startPreviewWorkerIfNeeded()
        val operation = if (blockForRelease) "stopRecording" else "stopRecording_queue"
        V2AppLog.perf(TAG, operation, SystemClock.elapsedRealtime() - startedMs, "blockForRelease=$blockForRelease")
        publishStatus()
    }

    private fun handleRecorderFailure(message: String) {
        mainHandler.post {
            if (!recording && pipeline == null) return@post
            V2AppLog.e(TAG, "recorder failure: $message")
            recording = false
            normalRecording = false
            recordingStartedAtMs = 0L
            pipeline = null
            runCatching { nativeCompositor.setPreviewMaxFps(previewMaxFps) }
            publishStatus()
        }
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
        private const val EVENT_SEGMENT_GUARD_MS = 5_000L
    }
}
