package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.NativeMetricsSnapshot
import com.kooo.evcam.v2.nativebridge.V2NativeRecordingBridge
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.storage.V2StorageCleanupResult
import com.kooo.evcam.v2.storage.V2StorageCleaner
import com.kooo.evcam.v2.ui.V2TimeWatermark
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class V2CompositeRecorder(
    private val context: Context,
    private val outputDir: File,
    private val nativeHandle: Long,
    private val renderHandler: Handler,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoBitrate: Int,
    private val recordingFps: Int,
    private val segmentDurationMs: Long,
    private val fileSuffix: String,
    private val onFailure: (String) -> Unit = {},
) : V2RecordingPipeline {
    companion object {
        private const val START_CAPTURE_TIMEOUT_MS = 2_000L
        private const val STOP_RENDER_TIMEOUT_MS = 2_000L
        private const val MIME_VIDEO_AVC = "video/avc"
    }

    private val native = V2NativeRecordingBridge(nativeHandle)
    private val metrics = RecordingMetrics()
    private val stopRequested = AtomicBoolean(true)
    private val watermarkHandler = Handler(Looper.getMainLooper())
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val emergencyClips = V2EmergencyClipCoordinator(
        context = context,
        outputDir = outputDir,
        isRecording = { recording },
        stoppedWallClockMs = { stoppedWallClockMs },
    )

    private var recording = false
    private var generation = 0L
    private var nativeWorkerActive = false
    @Volatile private var cleanupFuture: Future<V2StorageCleanupResult>? = null
    @Volatile private var stoppedWallClockMs = 0L
    private val watermarkTicker = object : Runnable {
        override fun run() {
            if (!recording || stopRequested.get()) return
            updateWatermarkAsync(System.currentTimeMillis())
            watermarkHandler.postDelayed(this, V2TimeWatermark.nextSecondDelayMs())
        }
    }

    override fun start(): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "start outputSize=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$recordingFps segmentMs=$segmentDurationMs mime=$MIME_VIDEO_AVC backend=ZigManaged outputDir=${outputDir.absolutePath}")
        V2RecordingSegmentCacheUpdater.initialize(context)
        metrics.apply {
            requestedFrames = 0
            renderedFrames = 0
            encodedSamples = 0
            droppedFrames = 0
            segmentIndex = 0
            segmentSwitchMs = 0
            firstSampleLatencyMs = -1
            pipeLockAcquireCount = 0
            pipeLockWaitTotalMs = 0
            pipeLockWaitMaxMs = 0
            pipeTryLockSuccessCount = 0
            pipeTryLockFailCount = 0
            recordingLockDeferCount = 0
            recordingPreviewYieldCount = 0
            recordingQueueProducedCount = 0
            recordingQueueConsumedCount = 0
            recordingQueueDropCount = 0
            recordingQueueDepth = 0
            recordingQueueMaxDepth = 0
            recordingQueueFallbackCount = 0
            recordingQueueFboRecreateCount = 0
            lastError = "无"
        }
        val startWallClockMs = System.currentTimeMillis()
        val initialWatermark = createWatermarkBitmap(startWallClockMs)
        recording = true
        stopRequested.set(false)
        generation += 1
        val startGeneration = generation
        val reservedBytes = V2StorageCleanupSettings.reservedSpaceBytes(context)
        val startResult = runOnCaptureSync {
            uploadInitialWatermark(initialWatermark)
            check(native.startManagedRecording(
                outputDir = outputDir.absolutePath,
                suffix = fileSuffix,
                width = outputWidth,
                height = outputHeight,
                bitrate = videoBitrate,
                fps = recordingFps,
                segmentDurationMs = segmentDurationMs,
                wallClockMs = startWallClockMs,
                reservedBytes = reservedBytes,
                availableBytes = outputDir.usableSpace,
            )) { "Native managed recording start failed: ${native.lastError()}" }
        }
        startResult.onFailure {
            metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "启动失败")
            recording = false
            stopRequested.set(true)
            generation += 1
            runCatching { native.stopManagedRecording(0L, startWallClockMs) }
                .onFailure { stopError -> V2AppLog.e("V2CompositeRecorder", "stop native after start failure failed", stopError) }
            V2AppLog.e("V2CompositeRecorder", "start failed", it)
            return false
        }
        nativeWorkerActive = true
        scheduleWatermarkUpdates()
        scheduleStorageCleanup()
        V2AppLog.i("V2CompositeRecorder", "native managed recording worker started generation=$startGeneration ${workerSnapshotSummary()}")
        V2AppLog.perf("V2CompositeRecorder", "start", SystemClock.elapsedRealtime() - startedMs)
        return true
    }

    override fun stop() {
        val startedMs = SystemClock.elapsedRealtime()
        if (!stopRequested.compareAndSet(false, true)) {
            V2AppLog.i("V2CompositeRecorder", "stop ignored: already stopping/stopped")
            return
        }
        V2AppLog.i("V2CompositeRecorder", "stop requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        stopManagedRecording(STOP_RENDER_TIMEOUT_MS, System.currentTimeMillis())
        V2AppLog.perf("V2CompositeRecorder", "stop_queue", SystemClock.elapsedRealtime() - startedMs)
    }

    override fun stopBlockingForRelease(timeoutMs: Long) {
        val startedMs = SystemClock.elapsedRealtime()
        if (!stopRequested.compareAndSet(false, true)) {
            V2AppLog.i("V2CompositeRecorder", "stopBlockingForRelease ignored: already stopping/stopped")
            return
        }
        V2AppLog.i("V2CompositeRecorder", "stopBlockingForRelease requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        stopManagedRecording(STOP_RENDER_TIMEOUT_MS.coerceAtMost(timeoutMs), System.currentTimeMillis())
        V2AppLog.perf("V2CompositeRecorder", "stopBlockingForRelease", SystemClock.elapsedRealtime() - startedMs)
    }

    override fun requestEmergencyClip(startWallClockMs: Long, durationMs: Long): Boolean {
        if (fileSuffix.isNotEmpty() || durationMs <= 0L) return false
        return emergencyClips.request(startWallClockMs, durationMs)
    }

    override fun metricsSnapshot(): RecordingMetrics {
        syncMetricsFromNative()
        return metrics.copy()
    }

    private fun stopManagedRecording(timeoutMs: Long, stopWallClockMs: Long) {
        recording = false
        stopWatermarkUpdates()
        stoppedWallClockMs = stopWallClockMs
        syncMetricsFromNative()
        runCatching { native.stopManagedRecording(timeoutMs, stopWallClockMs) }
            .onFailure {
                metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "停止失败")
                V2AppLog.e("V2CompositeRecorder", "native managed stop failed", it)
            }
        runCatching { native.clearWatermarkBitmap() }
        nativeWorkerActive = false
        generation += 1
        finishEmergencyClipExportsIfStopped()
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
    }

    private fun workerSnapshotSummary(): String {
        val s = native.snapshotWorker()
        if (s.size < 8) return "workerSnapshot=unavailable"
        return "worker running=${s[0]} stop=${s[1]} paused=${s[2]} writer=${s[3]} event=${s[4]} gen=${s[5]} req=${s[6]} rendered=${s[7]}"
    }

    private fun syncMetricsFromNative() {
        val s = native.metricsSnapshot()
        if (s.size < 9) return
        metrics.requestedFrames = maxOf(metrics.requestedFrames, s[NativeMetricsSnapshot.RECORDING_REQUESTED_FRAMES].coerceAtLeast(0L))
        metrics.renderedFrames = maxOf(metrics.renderedFrames, s[NativeMetricsSnapshot.RECORDING_RENDERED_FRAMES].coerceAtLeast(0L))
        metrics.droppedFrames = maxOf(metrics.droppedFrames, s[NativeMetricsSnapshot.RECORDING_DROPPED_FRAMES].coerceAtLeast(0L))
        metrics.segmentIndex = maxOf(metrics.segmentIndex, s[NativeMetricsSnapshot.RECORDING_SEGMENT_INDEX].toInt())
        if (s.size > NativeMetricsSnapshot.RECORDING_ENCODED_SAMPLES) {
            metrics.encodedSamples = maxOf(metrics.encodedSamples, s[NativeMetricsSnapshot.RECORDING_ENCODED_SAMPLES].coerceAtLeast(0L))
        }
        if (s.size > NativeMetricsSnapshot.RECORDING_PREVIEW_YIELD_COUNT) {
            metrics.pipeLockAcquireCount = maxOf(metrics.pipeLockAcquireCount, s[NativeMetricsSnapshot.PIPE_LOCK_ACQUIRE_COUNT].coerceAtLeast(0L))
            metrics.pipeLockWaitTotalMs = maxOf(metrics.pipeLockWaitTotalMs, s[NativeMetricsSnapshot.PIPE_LOCK_WAIT_TOTAL_MS].coerceAtLeast(0L))
            metrics.pipeLockWaitMaxMs = maxOf(metrics.pipeLockWaitMaxMs, s[NativeMetricsSnapshot.PIPE_LOCK_WAIT_MAX_MS].coerceAtLeast(0L))
            metrics.pipeTryLockSuccessCount = maxOf(metrics.pipeTryLockSuccessCount, s[NativeMetricsSnapshot.PIPE_TRY_LOCK_SUCCESS_COUNT].coerceAtLeast(0L))
            metrics.pipeTryLockFailCount = maxOf(metrics.pipeTryLockFailCount, s[NativeMetricsSnapshot.PIPE_TRY_LOCK_FAIL_COUNT].coerceAtLeast(0L))
            metrics.recordingLockDeferCount = maxOf(metrics.recordingLockDeferCount, s[NativeMetricsSnapshot.RECORDING_LOCK_DEFER_COUNT].coerceAtLeast(0L))
            metrics.recordingPreviewYieldCount = maxOf(metrics.recordingPreviewYieldCount, s[NativeMetricsSnapshot.RECORDING_PREVIEW_YIELD_COUNT].coerceAtLeast(0L))
        }
        if (s.size > NativeMetricsSnapshot.RECORDING_QUEUE_FBO_RECREATE_COUNT) {
            metrics.recordingQueueProducedCount = maxOf(metrics.recordingQueueProducedCount, s[NativeMetricsSnapshot.RECORDING_QUEUE_PRODUCED_COUNT].coerceAtLeast(0L))
            metrics.recordingQueueConsumedCount = maxOf(metrics.recordingQueueConsumedCount, s[NativeMetricsSnapshot.RECORDING_QUEUE_CONSUMED_COUNT].coerceAtLeast(0L))
            metrics.recordingQueueDropCount = maxOf(metrics.recordingQueueDropCount, s[NativeMetricsSnapshot.RECORDING_QUEUE_DROP_COUNT].coerceAtLeast(0L))
            metrics.recordingQueueDepth = s[NativeMetricsSnapshot.RECORDING_QUEUE_DEPTH].coerceAtLeast(0L)
            metrics.recordingQueueMaxDepth = maxOf(metrics.recordingQueueMaxDepth, s[NativeMetricsSnapshot.RECORDING_QUEUE_MAX_DEPTH].coerceAtLeast(0L))
            metrics.recordingQueueFallbackCount = maxOf(metrics.recordingQueueFallbackCount, s[NativeMetricsSnapshot.RECORDING_QUEUE_FALLBACK_COUNT].coerceAtLeast(0L))
            metrics.recordingQueueFboRecreateCount = maxOf(metrics.recordingQueueFboRecreateCount, s[NativeMetricsSnapshot.RECORDING_QUEUE_FBO_RECREATE_COUNT].coerceAtLeast(0L))
        }
    }

    private fun scheduleStorageCleanup() {
        val future = cleanupFuture
        if (future != null && !future.isDone) return
        cleanupFuture = cleanupExecutor.submit<V2StorageCleanupResult> {
            runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
                .onSuccess { logCleanupResult("background", it) }
                .getOrElse {
                    V2AppLog.w("V2CompositeRecorder", "storage cleanup background failed", it)
                    V2StorageCleanupResult(0, 0L, outputDir.usableSpace, 0L)
                }
        }
    }

    private fun logCleanupResult(stage: String, result: V2StorageCleanupResult) {
        if (result.deletedCount > 0) {
            V2AppLog.w("V2CompositeRecorder", "storage cleanup $stage deleted=${result.deletedCount} freed=${V2StorageCleaner.formatBytes(result.deletedBytes)} available=${V2StorageCleaner.formatBytes(result.availableBytes)} reserve=${V2StorageCleaner.formatBytes(result.reservedBytes)}")
        }
    }

    private fun finishEmergencyClipExportsIfStopped() {
        emergencyClips.finishExportsIfStopped()
    }

    private fun scheduleWatermarkUpdates() {
        watermarkHandler.removeCallbacks(watermarkTicker)
        watermarkHandler.postDelayed(watermarkTicker, V2TimeWatermark.nextSecondDelayMs())
    }

    private fun stopWatermarkUpdates() {
        watermarkHandler.removeCallbacks(watermarkTicker)
    }

    private fun updateWatermarkAsync(wallClockMs: Long) {
        val bitmap = createWatermarkBitmap(wallClockMs) ?: return
        renderHandler.post {
            try {
                if (recording && !stopRequested.get()) uploadWatermarkBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun createWatermarkBitmap(wallClockMs: Long): Bitmap? =
        runCatching { V2TimeWatermark.renderBitmap(context, wallClockMs) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "render watermark failed", it) }
            .getOrNull()

    private fun uploadWatermarkBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        val uploaded = native.updateWatermarkBitmap(
            bitmap,
            V2TimeWatermark.overlayX(context),
            V2TimeWatermark.overlayY(context),
        )
        if (!uploaded) V2AppLog.w("V2CompositeRecorder", "native watermark upload failed: ${native.lastError()}")
    }

    private fun uploadInitialWatermark(bitmap: Bitmap?) {
        try {
            uploadWatermarkBitmap(bitmap)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun runOnCaptureSync(block: () -> Unit): Result<Unit> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        renderHandler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(START_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return Result.failure(java.lang.IllegalStateException("capture init timed out after ${START_CAPTURE_TIMEOUT_MS}ms"))
        }
        return result.get() ?: Result.failure(java.lang.IllegalStateException("capture init failed"))
    }
}
