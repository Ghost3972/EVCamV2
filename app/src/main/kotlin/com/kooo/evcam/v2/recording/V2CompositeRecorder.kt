package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.VulkanNative
import com.kooo.evcam.v2.storage.V2StorageCleaner
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
    segmentPrecreateEnabled: Boolean,
) {
    companion object {
        private const val TICK_SHOULD_RENDER = 1L
        private const val TICK_DROPPED = 2L
        private const val TICK_SEGMENT_DUE = 4L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metrics = RecordingMetrics()
    private var recording = false
    private var generation = 0L
    private var writer: EncoderSegmentWriter? = null
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val segmentPrepareExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cleanupFuture: Future<V2StorageCleaner.CleanupResult>? = null
    private var preparedSegmentIndex = -1
    private var preparedSegmentWallClockMs = 0L
    private var preparedSegmentFuture: Future<EncoderSegmentWriter>? = null
    private var segmentPrecreateEnabled = segmentPrecreateEnabled

    fun start(): Boolean {
        V2AppLog.i("V2CompositeRecorder", "start output=${outputDir.absolutePath} size=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$recordingFps segmentMs=$segmentDurationMs precreate=$segmentPrecreateEnabled")
        metrics.apply {
            requestedFrames = 0; renderedFrames = 0; encodedSamples = 0; droppedFrames = 0
            segmentIndex = 0; segmentSwitchMs = 0; firstSampleLatencyMs = -1; lastError = "无"
        }
        runStorageCleanupBlocking()
        val firstSegmentWallClockMs = VulkanNative.startRecordingSession(nativeHandle, recordingFps, segmentDurationMs, System.currentTimeMillis())
        recording = true
        generation += 1
        val startGeneration = generation
        val startResult = runOnCaptureSync { startNewSegment(0, firstSegmentWallClockMs) }
        startResult.onFailure {
            metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "启动失败")
            recording = false
            VulkanNative.stopRecordingSession(nativeHandle)
            V2AppLog.e("V2CompositeRecorder", "start failed", it)
            return false
        }
        scheduleRecordingTick(startGeneration, 0L)
        return true
    }

    fun stop() {
        V2AppLog.i("V2CompositeRecorder", "stop requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
        VulkanNative.stopRecordingSession(nativeHandle)
        val stopGeneration = ++generation
        val writerToStop = writer
        writer = null
        val releaseQueued = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                if (stopGeneration != generation) return@post
                writerToStop?.requestDrain()
                if (writerToStop != null) {
                    runCatching {
                        if (VulkanNative.renderCompositor(nativeHandle)) {
                            metrics.renderedFrames += 1
                            writerToStop.requestDrain()
                        }
                    }.onFailure { t ->
                        metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "停止补帧失败")
                        V2AppLog.e("V2CompositeRecorder", "stop final render failed", t)
                    }
                }
                runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                    .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface on stop failed", it) }
            } finally {
                latch.countDown()
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true)
                }
                releasePreparedSegmentAsync()
                releaseExecutor.shutdown()
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!latch.await(2_000L, TimeUnit.MILLISECONDS)) {
                V2AppLog.e("V2CompositeRecorder", "stop timed out; release writer without final render")
                generation += 1
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true)
                }
                releasePreparedSegmentAsync()
                releaseExecutor.shutdown()
            }
        } else {
            V2AppLog.i("V2CompositeRecorder", "stop queued without blocking main thread")
        }
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        segmentPrepareExecutor.shutdown()
    }

    fun metricsSnapshot(): RecordingMetrics = metrics.copy()

    private fun startNewSegment(segmentIndex: Int, segmentWallClockMs: Long) {
        val segmentStartMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "start segment index=$segmentIndex wallClockMs=$segmentWallClockMs preparedFuture=${preparedSegmentFuture != null} precreate=$segmentPrecreateEnabled")
        consumeFinishedCleanupResult()
        val newWriter = takePreparedSegment(segmentIndex, segmentWallClockMs)
        val oldWriter = writer
        if (newWriter == null && oldWriter != null) {
            runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface before sync segment switch failed", it) }
            releaseWriterAsync(oldWriter, finish = true)
            writer = null
        }
        val segmentWriter = newWriter
            ?: EncoderSegmentWriter(outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
            }
        val surface = segmentWriter.surface ?: throw IllegalStateException("Encoder surface unavailable")
        try {
            if (nativeHandle == 0L) throw java.lang.IllegalStateException(VulkanNative.getLastError())
            if (!VulkanNative.attachEncoderSurface(nativeHandle, surface)) throw java.lang.IllegalStateException(VulkanNative.getLastError())
        } catch (t: Throwable) {
            releaseWriterAsync(segmentWriter, finish = false)
            throw t
        }
        segmentWriter.markAttached(segmentIndex)
        writer = segmentWriter
        if (newWriter != null) oldWriter?.let { releaseWriterAsync(it, finish = true) }
        metrics.segmentIndex = segmentIndex
        V2AppLog.i("V2CompositeRecorder", "segment attached index=$segmentIndex file=${segmentWriter.currentFile()?.name} prepared=${newWriter != null} switchSetupMs=${SystemClock.elapsedRealtime() - segmentStartMs}")
        prepareNextSegment(segmentIndex + 1, segmentWallClockMs + segmentDurationMs)
        scheduleStorageCleanup()
    }

    private fun prepareNextSegment(segmentIndex: Int, segmentWallClockMs: Long) {
        if (!segmentPrecreateEnabled) return
        val existing = preparedSegmentFuture
        if (existing != null && !existing.isDone && preparedSegmentIndex == segmentIndex && preparedSegmentWallClockMs == segmentWallClockMs) return
        if (existing != null && existing.isDone && preparedSegmentIndex == segmentIndex && preparedSegmentWallClockMs == segmentWallClockMs) return
        releasePreparedSegmentAsync()
        preparedSegmentIndex = segmentIndex
        preparedSegmentWallClockMs = segmentWallClockMs
        preparedSegmentFuture = segmentPrepareExecutor.submit<EncoderSegmentWriter> {
            val startedMs = SystemClock.elapsedRealtime()
            EncoderSegmentWriter(outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
                V2AppLog.i("V2CompositeRecorder", "prepared segment index=$segmentIndex file=${it.currentFile()?.name} prepareMs=${SystemClock.elapsedRealtime() - startedMs}")
            }
        }
    }

    private fun takePreparedSegment(segmentIndex: Int, segmentWallClockMs: Long): EncoderSegmentWriter? {
        val future = preparedSegmentFuture ?: return null
        if (preparedSegmentIndex != segmentIndex || preparedSegmentWallClockMs != segmentWallClockMs) return null
        if (!future.isDone) {
            segmentPrecreateEnabled = false
            V2AppLog.w("V2CompositeRecorder", "prepared segment still busy at switch index=$segmentIndex; disable precreate and fall back")
            releasePreparedSegmentAsync()
            return null
        }
        val waitStartedMs = SystemClock.elapsedRealtime()
        return runCatching { future.get() }
            .onSuccess {
                preparedSegmentFuture = null
                preparedSegmentIndex = -1
                preparedSegmentWallClockMs = 0L
                V2AppLog.i("V2CompositeRecorder", "prepared segment taken index=$segmentIndex waitMs=${SystemClock.elapsedRealtime() - waitStartedMs}")
            }
            .onFailure {
                segmentPrecreateEnabled = false
                releasePreparedSegmentAsync()
                V2AppLog.w("V2CompositeRecorder", "prepared segment unavailable; disable precreate index=$segmentIndex waitMs=${SystemClock.elapsedRealtime() - waitStartedMs}", it)
            }
            .getOrNull()
    }

    private fun releasePreparedSegmentAsync() {
        val future = preparedSegmentFuture ?: return
        preparedSegmentFuture = null
        preparedSegmentIndex = -1
        preparedSegmentWallClockMs = 0L
        releaseExecutor.execute {
            runCatching {
                future.get().releaseBlocking()
            }.onFailure { V2AppLog.w("V2CompositeRecorder", "release prepared segment failed", it) }
        }
    }

    private fun scheduleRecordingTick(tickGeneration: Long, delayMs: Long) {
        renderHandler.postDelayed({ drawTick(tickGeneration) }, delayMs.coerceAtLeast(0L))
    }

    private fun drawTick(tickGeneration: Long) {
        if (!recording || tickGeneration != generation || writer == null) return
        try {
            metrics.requestedFrames += 1
            val tick = VulkanNative.recordingTickAndRender(nativeHandle, System.currentTimeMillis())
            if (tick < 0L) throw java.lang.IllegalStateException(VulkanNative.getLastError())
            val shouldRender = tick and TICK_SHOULD_RENDER != 0L
            val segmentDue = tick and TICK_SEGMENT_DUE != 0L
            if (tick and TICK_DROPPED != 0L) metrics.droppedFrames += 1

            if (shouldRender) {
                metrics.renderedFrames += 1
                writer?.requestDrain()
            }

            if (segmentDue) {
                val nextIndex = (tick ushr 32).toInt().coerceAtLeast(metrics.segmentIndex + 1)
                val switchStartedMs = SystemClock.elapsedRealtime()
                val segmentWallClockMs = VulkanNative.beginNextRecordingSegment(nativeHandle)
                runCatching { startNewSegment(nextIndex, segmentWallClockMs) }
                    .onSuccess { VulkanNative.completeRecordingSegmentSwitch(nativeHandle, true) }
                    .onFailure {
                        VulkanNative.completeRecordingSegmentSwitch(nativeHandle, false)
                        throw it
                    }
                metrics.segmentSwitchMs = SystemClock.elapsedRealtime() - switchStartedMs
            }
        } catch (t: Throwable) {
            metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "渲染失败")
            V2AppLog.e("V2CompositeRecorder", "render tick failed segment=${metrics.segmentIndex}", t)
            failStopOnRenderThread()
        } finally {
            if (recording && tickGeneration == generation) {
                val nextDelay = VulkanNative.getRecordingNextTickDelayMs(nativeHandle).takeIf { it >= 0L } ?: (1000L / recordingFps.coerceAtLeast(1))
                scheduleRecordingTick(tickGeneration, nextDelay)
            }
        }
    }

    private fun failStopOnRenderThread() {
        if (!recording) return
        recording = false
        generation += 1
        runCatching { VulkanNative.stopRecordingSession(nativeHandle) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "stop native after render failure failed", it) }
        runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder after render failure failed", it) }
        writer?.let { releaseWriterAsync(it, finish = false) }
        releasePreparedSegmentAsync()
        writer = null
    }

    private fun runStorageCleanupBlocking() {
        runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
            .onSuccess { logCleanupResult("before start", it) }
            .onFailure { V2AppLog.w("V2CompositeRecorder", "storage cleanup before start failed", it) }
    }

    private fun scheduleStorageCleanup() {
        val future = cleanupFuture
        if (future != null && !future.isDone) return
        cleanupFuture = cleanupExecutor.submit<V2StorageCleaner.CleanupResult> {
            runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
                .onSuccess { logCleanupResult("background", it) }
                .getOrElse {
                    V2AppLog.w("V2CompositeRecorder", "storage cleanup background failed", it)
                    V2StorageCleaner.CleanupResult(0, 0L, outputDir.usableSpace, 0L)
                }
        }
    }

    private fun consumeFinishedCleanupResult() {
        val future = cleanupFuture ?: return
        if (!future.isDone) return
        runCatching { future.get() }.onFailure { V2AppLog.w("V2CompositeRecorder", "storage cleanup result failed", it) }
        cleanupFuture = null
    }

    private fun logCleanupResult(stage: String, result: V2StorageCleaner.CleanupResult) {
        if (result.deletedCount > 0) {
            V2AppLog.w("V2CompositeRecorder", "storage cleanup $stage deleted=${result.deletedCount} freed=${V2StorageCleaner.formatBytes(result.deletedBytes)} available=${V2StorageCleaner.formatBytes(result.availableBytes)} reserve=${V2StorageCleaner.formatBytes(result.reservedBytes)}")
        }
    }

    private fun releaseWriterAsync(writer: EncoderSegmentWriter, finish: Boolean) {
        releaseExecutor.execute {
            V2AppLog.i("V2CompositeRecorder", "release writer finish=$finish file=${writer.currentFile()?.name}")
            if (finish) writer.finishAndReleaseBlocking() else writer.releaseBlocking()
        }
    }

    private fun runOnCaptureSync(block: () -> Unit): Result<Unit> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        renderHandler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        latch.await()
        return result.get() ?: Result.failure(java.lang.IllegalStateException("capture init failed"))
    }

}
