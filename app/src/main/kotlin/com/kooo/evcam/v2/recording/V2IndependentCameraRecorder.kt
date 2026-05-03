package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCameraRecordingBridge
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.storage.V2StorageCleanupResult
import com.kooo.evcam.v2.storage.V2StorageCleaner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class V2IndependentCameraRecorder(
    private val context: Context,
    private val outputDir: File,
    private val renderHandler: Handler,
    private val cameraTargets: List<V2RecordingPipelineFactory.CameraTarget>,
    private val videoBitrate: Int,
    private val recordingFps: Int,
    private val segmentDurationMs: Long,
    private val fileSuffix: String,
    private val onFailure: (String) -> Unit = {},
) : V2RecordingPipeline {
    private val metrics = RecordingMetrics()
    private val stopRequested = AtomicBoolean(true)
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val nativeStopExecutor = Executors.newFixedThreadPool(MAX_CAMERA_STOP_THREADS)
    private val emergencyClips = V2EmergencyClipCoordinator(
        context = context,
        outputDir = outputDir,
        isRecording = { recording },
        stoppedWallClockMs = { stoppedWallClockMs },
    )
    private val active = ArrayList<V2NativeCameraRecordingBridge>()
    private var recording = false
    @Volatile private var stoppedWallClockMs = 0L

    override fun start(): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        if (cameraTargets.isEmpty()) {
            metrics.lastError = "没有可录制摄像头"
            return false
        }
        V2RecordingSegmentCacheUpdater.initialize(context)
        metrics.apply {
            requestedFrames = 0
            renderedFrames = 0
            encodedSamples = 0
            droppedFrames = 0
            segmentIndex = 0
            segmentSwitchMs = 0
            firstSampleLatencyMs = -1
            lastError = "无"
        }
        recording = true
        stopRequested.set(false)
        val reservedBytes = V2StorageCleanupSettings.reservedSpaceBytes(context)
        val wallClockMs = System.currentTimeMillis()
        val perCameraBitrate = (videoBitrate / cameraTargets.size.coerceAtLeast(1)).coerceAtLeast(MIN_PER_CAMERA_BITRATE)
        val result = runOnNativeSync {
            cameraTargets.forEach { target ->
                val bridge = V2NativeCameraRecordingBridge(target.cameraHandle)
                check(bridge.startRecording(
                    outputDir = outputDir.absolutePath,
                    suffix = fileSuffix,
                    label = target.label,
                    width = target.width,
                    height = target.height,
                    bitrate = perCameraBitrate,
                    fps = recordingFps,
                    segmentDurationMs = segmentDurationMs,
                    wallClockMs = wallClockMs,
                    reservedBytes = reservedBytes,
                    availableBytes = outputDir.usableSpace,
                )) { "Native camera recording start failed ${target.label}: ${bridge.lastError()}" }
                active += bridge
            }
        }
        result.onFailure {
            metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "启动失败")
            V2AppLog.e(TAG, "start failed", it)
            stopActive(wallClockMs)
            recording = false
            stopRequested.set(true)
            onFailure(metrics.lastError)
            return false
        }
        scheduleStorageCleanup()
        V2AppLog.i(TAG, "independent recording started cameras=${cameraTargets.joinToString { it.label }} size=${cameraTargets.first().width}x${cameraTargets.first().height} bitrateEach=$perCameraBitrate fps=$recordingFps segmentMs=$segmentDurationMs suffix=$fileSuffix")
        V2AppLog.perf(TAG, "start", SystemClock.elapsedRealtime() - startedMs)
        return true
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        val stopMs = System.currentTimeMillis()
        stoppedWallClockMs = stopMs
        syncMetricsFromNative()
        runOnNativeAsync { stopActive(stopMs) }
    }

    override fun stopBlockingForRelease(timeoutMs: Long) {
        if (!stopRequested.compareAndSet(false, true)) return
        val stopMs = System.currentTimeMillis()
        stoppedWallClockMs = stopMs
        syncMetricsFromNative()
        runOnNativeSync { stopActive(stopMs) }
    }

    override fun requestEmergencyClip(startWallClockMs: Long, durationMs: Long): Boolean {
        if (fileSuffix.isNotEmpty() || durationMs <= 0L) return false
        return emergencyClips.request(startWallClockMs, durationMs)
    }

    override fun metricsSnapshot(): RecordingMetrics {
        syncMetricsFromNative()
        return metrics.copy()
    }

    private fun stopActive(stopWallClockMs: Long) {
        val copy = active.toList()
        active.clear()
        copy.forEach { bridge ->
            nativeStopExecutor.submit<Boolean> {
                runCatching { bridge.stopRecording(STOP_TIMEOUT_MS, stopWallClockMs) }
                    .onSuccess { stopped ->
                        if (!stopped) {
                            metrics.lastError = "native camera stop returned false"
                            V2AppLog.e(TAG, metrics.lastError)
                        }
                    }
                    .onFailure {
                        metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "停止失败")
                        V2AppLog.e(TAG, "stop native camera recording failed", it)
                    }
                    .getOrDefault(false)
            }
        }
        recording = false
        finishEmergencyClipExportsIfStopped()
        cleanupExecutor.shutdown()
        nativeStopExecutor.shutdown()
    }

    private fun syncMetricsFromNative() {
        var samples = 0L
        var segment = 0
        active.forEach { bridge ->
            val snapshot = bridge.snapshot()
            if (snapshot.size >= 5) {
                segment = maxOf(segment, snapshot[3].toInt())
                samples += snapshot[4].coerceAtLeast(0L)
            }
        }
        metrics.segmentIndex = maxOf(metrics.segmentIndex, segment)
        metrics.encodedSamples = maxOf(metrics.encodedSamples, samples)
        metrics.renderedFrames = maxOf(metrics.renderedFrames, samples)
        metrics.requestedFrames = maxOf(metrics.requestedFrames, samples)
    }

    private fun scheduleStorageCleanup() {
        cleanupExecutor.submit<V2StorageCleanupResult> {
            runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
                .onSuccess {
                    if (it.deletedCount > 0) V2AppLog.w(TAG, "storage cleanup deleted=${it.deletedCount} freed=${V2StorageCleaner.formatBytes(it.deletedBytes)}")
                }
                .getOrElse {
                    V2AppLog.w(TAG, "storage cleanup failed", it)
                    V2StorageCleanupResult(0, 0L, outputDir.usableSpace, 0L)
                }
        }
    }

    private fun finishEmergencyClipExportsIfStopped() {
        emergencyClips.finishExportsIfStopped()
    }

    private fun runOnNativeSync(block: () -> Unit): Result<Unit> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        renderHandler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return Result.failure(IllegalStateException("camera recording init timed out after ${START_TIMEOUT_MS}ms"))
        }
        return result.get() ?: Result.failure(IllegalStateException("camera recording init failed"))
    }

    private fun runOnNativeAsync(block: () -> Unit) {
        renderHandler.post { runCatching(block).onFailure { V2AppLog.e(TAG, "async native recording op failed", it) } }
    }

    private companion object {
        const val TAG = "V2IndependentRecorder"
        const val START_TIMEOUT_MS = 4_000L
        const val STOP_TIMEOUT_MS = 2_000L
        const val MIN_PER_CAMERA_BITRATE = 2_000_000
        const val MAX_CAMERA_STOP_THREADS = 4
    }
}
