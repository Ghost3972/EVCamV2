package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeRecordingBridge
import com.kooo.evcam.v2.storage.V2StorageCleanupResult
import com.kooo.evcam.v2.storage.V2StorageCleaner
import java.io.File
import java.util.Locale
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
) {
    companion object {
        private const val START_CAPTURE_TIMEOUT_MS = 2_000L
        private const val STOP_RENDER_TIMEOUT_MS = 2_000L
        private const val STOP_WRITER_TIMEOUT_MS = 2_500L
        private const val RECORDING_SLOW_RENDER_MS = 24L
        private const val RECORDING_FRAME_PERF_LOG_INTERVAL_MS = 5_000L
        private const val RECORDING_FRAME_WARN_LOG_INTERVAL_MS = 1_000L
        private const val WORKER_POLL_INTERVAL_MS = 100L
        private const val MIME_VIDEO_AVC = "video/avc"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val native = V2NativeRecordingBridge(nativeHandle)
    private val metrics = RecordingMetrics()
    private var recording = false
    private var generation = 0L
    private var writer: V2NativeSegmentWriter? = null
    private var nativeWorkerActive = false
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val videoMimeType = MIME_VIDEO_AVC
    private val emergencyClips = V2EmergencyClipCoordinator(
        context = context,
        outputDir = outputDir,
        segmentDurationMs = segmentDurationMs,
        isRecording = { recording },
        stoppedWallClockMs = { stoppedWallClockMs },
    )
    @Volatile private var cleanupFuture: Future<V2StorageCleanupResult>? = null
    private var lastFramePerfLogMs = 0L
    private var lastFramePerfRequested = 0L
    private var lastFramePerfRendered = 0L
    private var lastFramePerfEncoded = 0L
    private var lastFramePerfDropped = 0L
    @Volatile private var stoppedWallClockMs = 0L

    fun start(): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "start size=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$recordingFps segmentMs=$segmentDurationMs mime=$videoMimeType backend=ZigNative outputDir=${outputDir.absolutePath}")
        metrics.apply {
            requestedFrames = 0; renderedFrames = 0; encodedSamples = 0; droppedFrames = 0
            segmentIndex = 0; segmentSwitchMs = 0; firstSampleLatencyMs = -1; lastError = "无"
        }
        scheduleStorageCleanup()
        val startWallClockMs = System.currentTimeMillis()
        val firstSegmentWallClockMs = native.startSession(recordingFps, segmentDurationMs, startWallClockMs)
        recording = true
        generation += 1
        val startGeneration = generation
        val startResult = runOnCaptureSync {
            if (!recording || generation != startGeneration) return@runOnCaptureSync
            startNewSegment(0, firstSegmentWallClockMs)
        }
        startResult.onFailure {
            metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "启动失败")
            recording = false
            generation += 1
            native.stopSession()
            runCatching { native.detachEncoderSurface() }
                .onFailure { detachError -> V2AppLog.e("V2CompositeRecorder", "detach encoder after start failure failed", detachError) }
            writer?.let { releaseWriterAsync(it, finish = false) }
            writer = null
            V2AppLog.e("V2CompositeRecorder", "start failed", it)
            return false
        }
        val currentWriter = writer
        if (currentWriter == null || !native.startWorker(currentWriter.nativeHandle, recordingFps)) {
            val error = "native recording worker unavailable: ${native.lastError()}"
            metrics.lastError = error
            recording = false
            generation += 1
            native.stopSession()
            runCatching { native.detachEncoderSurface() }
                .onFailure { detachError -> V2AppLog.e("V2CompositeRecorder", "detach encoder after worker start failure failed", detachError) }
            writer?.let { releaseWriterAsync(it, finish = false) }
            writer = null
            V2AppLog.e("V2CompositeRecorder", error)
            return false
        }
        nativeWorkerActive = true
        scheduleWorkerPoll(startGeneration, WORKER_POLL_INTERVAL_MS)
        V2AppLog.i("V2CompositeRecorder", "native recording worker started ${workerSnapshotSummary()}")
        V2AppLog.perf("V2CompositeRecorder", "start", SystemClock.elapsedRealtime() - startedMs)
        return true
    }

    fun stop() {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "stop requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
        stopNativeWorkerIfActive("stop", STOP_RENDER_TIMEOUT_MS)
        nativeWorkerActive = false
        native.stopSession()
        val stopGeneration = ++generation
        val writerToStop = writer
        val stopWallClockMs = System.currentTimeMillis()
        stoppedWallClockMs = stopWallClockMs
        writer = null
        val releaseQueued = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                if (stopGeneration != generation) return@post
                if (writerToStop != null) {
                    runCatching {
                        applyFinalRenderAndDrain(writerToStop, native.finalRenderAndDrain(writerToStop.nativeHandle, 10_000L))
                    }.onFailure { t ->
                        metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "停止补帧失败")
                        V2AppLog.e("V2CompositeRecorder", "stop final render failed", t)
                    }
                }
                runCatching { native.detachEncoderSurface() }
                    .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface on stop failed", it) }
            } finally {
                latch.countDown()
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true, segmentEndWallClockMs = stopWallClockMs)
                }
                releaseExecutor.shutdown()
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!latch.await(2_000L, TimeUnit.MILLISECONDS)) {
                V2AppLog.e("V2CompositeRecorder", "stop timed out; release writer without final render")
                generation += 1
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true, segmentEndWallClockMs = stopWallClockMs)
                }
                releaseExecutor.shutdown()
            }
        } else {
            V2AppLog.i("V2CompositeRecorder", "stop queued without blocking main thread")
        }
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        V2AppLog.perf("V2CompositeRecorder", "stop_queue", SystemClock.elapsedRealtime() - startedMs)
    }

    fun stopBlockingForRelease(timeoutMs: Long = STOP_RENDER_TIMEOUT_MS + STOP_WRITER_TIMEOUT_MS) {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "stopBlockingForRelease requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
        stopNativeWorkerIfActive("release", STOP_RENDER_TIMEOUT_MS)
        nativeWorkerActive = false
        native.stopSession()
        val stopGeneration = ++generation
        val writerToStop = writer
        val stopWallClockMs = System.currentTimeMillis()
        stoppedWallClockMs = stopWallClockMs
        writer = null
        val latch = CountDownLatch(1)
        val renderTask = Runnable {
            try {
                if (stopGeneration != generation) return@Runnable
                if (writerToStop != null) {
                    runCatching {
                        applyFinalRenderAndDrain(writerToStop, native.finalRenderAndDrain(writerToStop.nativeHandle, 10_000L))
                    }.onFailure { t ->
                        metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "停止补帧失败")
                        V2AppLog.e("V2CompositeRecorder", "release final render failed", t)
                    }
                }
                runCatching { native.detachEncoderSurface() }
                    .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface on release failed", it) }
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == renderHandler.looper) renderTask.run() else renderHandler.post(renderTask)
        if (Looper.myLooper() != renderHandler.looper && !latch.await(STOP_RENDER_TIMEOUT_MS.coerceAtMost(timeoutMs), TimeUnit.MILLISECONDS)) {
            V2AppLog.e("V2CompositeRecorder", "stopBlockingForRelease render detach timed out")
        }
        val finished = writerToStop?.finishAndReleaseBlocking()
        if (finished != null && fileSuffix.isEmpty()) {
            onSegmentFinalized(finished, writerToStop.mediaStartWallClockMs(), stopWallClockMs)
        }
        finishEmergencyClipExportsIfStopped()
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        releaseExecutor.shutdown()
        V2AppLog.perf("V2CompositeRecorder", "stopBlockingForRelease", SystemClock.elapsedRealtime() - startedMs)
    }

    fun requestEmergencyClip(startWallClockMs: Long, durationMs: Long): Boolean {
        if (fileSuffix.isNotEmpty() || durationMs <= 0L) return false
        return emergencyClips.request(startWallClockMs, durationMs)
    }

    fun metricsSnapshot(): RecordingMetrics = metrics.copy()

    private fun startNewSegment(segmentIndex: Int, segmentWallClockMs: Long) {
        val segmentStartMs = SystemClock.elapsedRealtime()
        val actualSegmentStartWallClockMs = System.currentTimeMillis()
        V2AppLog.d("V2CompositeRecorder", "start segment index=$segmentIndex")
        consumeFinishedCleanupResult()
        val oldWriter = writer
        if (oldWriter != null) {
            runCatching { native.detachEncoderSurface() }
                .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface before sync segment switch failed", it) }
            releaseWriterAsync(oldWriter, finish = true, segmentEndWallClockMs = actualSegmentStartWallClockMs)
            writer = null
        }
        val segmentWriter = createStartedSegmentWriter(segmentIndex, segmentWallClockMs)
        val surface = segmentWriter.surface ?: throw IllegalStateException("Encoder surface unavailable")
        try {
            if (!native.isAvailable) throw java.lang.IllegalStateException(native.lastError())
            segmentWriter.currentFile()?.let { finalFile ->
                native.setThumbnailPath(nativeThumbnailFile(finalFile).absolutePath)
            }
            if (!native.attachEncoderSurface(surface)) throw java.lang.IllegalStateException(native.lastError())
        } catch (t: Throwable) {
            releaseWriterAsync(segmentWriter, finish = false)
            throw t
        }
        writer = segmentWriter
        metrics.segmentIndex = segmentIndex
        V2AppLog.perf("V2CompositeRecorder", "segmentAttach", SystemClock.elapsedRealtime() - segmentStartMs, "index=$segmentIndex file=${segmentWriter.currentFile()?.name}")
        scheduleStorageCleanup()
    }

    private fun createStartedSegmentWriter(
        segmentIndex: Int,
        segmentWallClockMs: Long,
    ): V2NativeSegmentWriter {
        return createSegmentWriter().also {
            it.startSegment(segmentIndex, segmentWallClockMs)
            check(it.surface != null) { "Native encoder surface unavailable" }
        }
    }

    private fun createSegmentWriter(): V2NativeSegmentWriter = V2NativeSegmentWriter(
        config = V2SegmentWriterConfig(
            outputDir = outputDir,
            width = outputWidth,
            height = outputHeight,
            fps = recordingFps,
            bitrate = videoBitrate,
            fileSuffix = fileSuffix,
            mimeType = videoMimeType,
        )
    )

    private fun scheduleWorkerPoll(tickGeneration: Long, delayMs: Long) {
        renderHandler.postDelayed({ pollWorker(tickGeneration) }, delayMs.coerceAtLeast(0L))
    }

    private fun pollWorker(tickGeneration: Long) {
        if (!recording || tickGeneration != generation || writer == null || !nativeWorkerActive) return
        try {
            val pollStartedMs = SystemClock.elapsedRealtime()
            val tick = native.pollWorker()
            if (tick < 0L) {
                val label = V2NativeRecordingTickEvent.errorLabel(tick)
                throw java.lang.IllegalStateException("native worker error=$label native=${native.lastError()}")
            }
            if (tick != 0L) handleTickResult(tick, pollStartedMs, countRequested = true)
        } catch (t: Throwable) {
            metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "native worker failed")
            V2AppLog.e("V2CompositeRecorder", "native worker poll failed segment=${metrics.segmentIndex}", t)
            failStopOnRenderThread()
        } finally {
            if (recording && tickGeneration == generation && nativeWorkerActive) {
                scheduleWorkerPoll(tickGeneration, WORKER_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleTickResult(
        tick: Long,
        startedMs: Long,
        elapsedMs: Long = SystemClock.elapsedRealtime() - startedMs,
        countRequested: Boolean = true,
    ) {
        val currentWriter = writer ?: return
        val event = V2NativeRecordingTickEvent.parse(tick, metrics.segmentIndex)
        if (countRequested) metrics.requestedFrames += 1
        if (event.dropped) metrics.droppedFrames += 1
        if (event.shouldRender) {
            metrics.renderedFrames += 1
            if (event.drainedSamples > 0L) {
                metrics.encodedSamples += event.drainedSamples
                if (metrics.firstSampleLatencyMs < 0L) {
                    metrics.firstSampleLatencyMs = SystemClock.elapsedRealtime() - currentWriter.segmentStartedElapsedMs()
                }
            }
        }
        logRecordingFramePerfIfNeeded(elapsedMs, event.shouldRender, event.dropped)

        if (event.segmentDue) {
            val switchStartedMs = SystemClock.elapsedRealtime()
            if (nativeWorkerActive) {
                V2AppLog.i("V2CompositeRecorder", "native worker segmentDue before switch next=${event.nextSegmentIndex} ${workerSnapshotSummary()}")
            }
            val segmentWallClockMs = native.beginNextSegment()
            runCatching { startNewSegment(event.nextSegmentIndex, segmentWallClockMs) }
                .onSuccess {
                    native.completeSegmentSwitch(true)
                    if (nativeWorkerActive) {
                        val resumed = writer?.let { native.resumeWorker(it.nativeHandle) } ?: false
                        V2AppLog.i("V2CompositeRecorder", "native worker segment resume=$resumed next=${event.nextSegmentIndex} ${workerSnapshotSummary()}")
                    }
                }
                .onFailure {
                    native.completeSegmentSwitch(false)
                    throw it
                }
            metrics.segmentSwitchMs = SystemClock.elapsedRealtime() - switchStartedMs
        }
    }

    private fun applyFinalRenderAndDrain(writer: V2NativeSegmentWriter, tick: Long) {
        if (tick < 0L) throw java.lang.IllegalStateException(native.lastError())
        val event = V2NativeRecordingTickEvent.parse(tick, metrics.segmentIndex)
        if (event.shouldRender) metrics.renderedFrames += 1
        if (event.drainedSamples > 0L) {
            metrics.encodedSamples += event.drainedSamples
            if (metrics.firstSampleLatencyMs < 0L) {
                metrics.firstSampleLatencyMs = SystemClock.elapsedRealtime() - writer.segmentStartedElapsedMs()
            }
        }
    }

    private fun logRecordingFramePerfIfNeeded(renderMs: Long, shouldRender: Boolean, dropped: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val slow = renderMs >= RECORDING_SLOW_RENDER_MS
        val minInterval = if (dropped || slow) RECORDING_FRAME_WARN_LOG_INTERVAL_MS else RECORDING_FRAME_PERF_LOG_INTERVAL_MS
        if (now - lastFramePerfLogMs < minInterval) return
        val elapsedMs = (now - lastFramePerfLogMs).takeIf { it > 0L } ?: RECORDING_FRAME_PERF_LOG_INTERVAL_MS
        val requestedDelta = metrics.requestedFrames - lastFramePerfRequested
        val renderedDelta = metrics.renderedFrames - lastFramePerfRendered
        val encodedDelta = metrics.encodedSamples - lastFramePerfEncoded
        val droppedDelta = metrics.droppedFrames - lastFramePerfDropped
        lastFramePerfLogMs = now
        lastFramePerfRequested = metrics.requestedFrames
        lastFramePerfRendered = metrics.renderedFrames
        lastFramePerfEncoded = metrics.encodedSamples
        lastFramePerfDropped = metrics.droppedFrames
        V2AppLog.perf(
            "V2RecordingPerf",
            when {
                dropped -> "recordingFrame_dropped"
                slow -> "recordingFrame_slow"
                else -> "recordingFrame"
            },
            renderMs,
            "seg=${metrics.segmentIndex} reqFps=${rate(requestedDelta, elapsedMs)} renderFps=${rate(renderedDelta, elapsedMs)} encFps=${rate(encodedDelta, elapsedMs)} dropDelta=$droppedDelta totalDrop=${metrics.droppedFrames} rendered=$shouldRender firstSampleMs=${metrics.firstSampleLatencyMs}"
        )
    }

    private fun rate(delta: Long, elapsedMs: Long): String = String.format(Locale.US, "%.1f", delta.coerceAtLeast(0L) * 1000f / elapsedMs.coerceAtLeast(1L))

    private fun workerSnapshotSummary(): String {
        val s = native.snapshotWorker()
        if (s.size < 8) return "workerSnapshot=unavailable"
        return "worker running=${s[0]} stop=${s[1]} paused=${s[2]} writer=${s[3]} event=${s[4]} gen=${s[5]} req=${s[6]} rendered=${s[7]}"
    }

    private fun stopNativeWorkerIfActive(reason: String, timeoutMs: Long) {
        if (!nativeWorkerActive) return
        val stopStartedMs = SystemClock.elapsedRealtime()
        val event = native.stopWorker(timeoutMs)
        V2AppLog.perf("V2CompositeRecorder", "nativeWorkerStop", SystemClock.elapsedRealtime() - stopStartedMs, "reason=$reason event=$event ${workerSnapshotSummary()}")
    }

    private fun failStopOnRenderThread() {
        if (!recording) return
        val failureMessage = metrics.lastError
        recording = false
        stopNativeWorkerIfActive("failure", STOP_RENDER_TIMEOUT_MS)
        nativeWorkerActive = false
        generation += 1
        runCatching { native.stopSession() }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "stop native after render failure failed", it) }
        runCatching { native.detachEncoderSurface() }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder after render failure failed", it) }
        writer?.let { releaseWriterAsync(it, finish = false) }
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        writer = null
        mainHandler.post { onFailure(failureMessage) }
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

    private fun consumeFinishedCleanupResult() {
        val future = cleanupFuture ?: return
        if (!future.isDone) return
        runCatching { future.get() }.onFailure { V2AppLog.w("V2CompositeRecorder", "storage cleanup result failed", it) }
        cleanupFuture = null
    }

    private fun logCleanupResult(stage: String, result: V2StorageCleanupResult) {
        if (result.deletedCount > 0) {
            V2AppLog.w("V2CompositeRecorder", "storage cleanup $stage deleted=${result.deletedCount} freed=${V2StorageCleaner.formatBytes(result.deletedBytes)} available=${V2StorageCleaner.formatBytes(result.availableBytes)} reserve=${V2StorageCleaner.formatBytes(result.reservedBytes)}")
        }
    }

    private fun onSegmentFinalized(file: File, startWallClockMs: Long, endWallClockMs: Long) {
        emergencyClips.onSegmentFinalized(file, startWallClockMs, endWallClockMs)
    }

    private fun nativeThumbnailFile(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".bmp")

    private fun releaseWriterAsync(
        writer: V2NativeSegmentWriter,
        finish: Boolean,
        segmentEndWallClockMs: Long? = null,
    ) {
        releaseExecutor.execute {
            val startedMs = SystemClock.elapsedRealtime()
            V2AppLog.d("V2CompositeRecorder", "release writer finish=$finish file=${writer.currentFile()?.name}")
            val finished = if (finish) writer.finishAndReleaseBlocking() else writer.releaseBlocking()
            V2AppLog.perf("V2CompositeRecorder", "releaseWriter", SystemClock.elapsedRealtime() - startedMs, "finish=$finish file=${finished?.name ?: writer.currentFile()?.name}")
            if (finish && finished != null && fileSuffix.isEmpty() && segmentEndWallClockMs != null) {
                onSegmentFinalized(finished, writer.mediaStartWallClockMs(), segmentEndWallClockMs)
            }
            finishEmergencyClipExportsIfStopped()
        }
    }

    private fun finishEmergencyClipExportsIfStopped() {
        emergencyClips.finishExportsIfStopped()
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
