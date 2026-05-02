package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeRecordingBridge
import com.kooo.evcam.v2.storage.V2StorageCleanupResult
import com.kooo.evcam.v2.storage.V2StorageCleaner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

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
    segmentPrecreateEnabled: Boolean,
    private val h265Enabled: Boolean,
    private val onFailure: (String) -> Unit = {},
) {
    companion object {
        private const val TICK_SHOULD_RENDER = 1L
        private const val TICK_DROPPED = 2L
        private const val TICK_SEGMENT_DUE = 4L
        private const val START_CAPTURE_TIMEOUT_MS = 2_000L
        private const val STOP_RENDER_TIMEOUT_MS = 2_000L
        private const val STOP_WRITER_TIMEOUT_MS = 2_500L
        private const val RECORDING_SLOW_RENDER_MS = 24L
        private const val RECORDING_FRAME_PERF_LOG_INTERVAL_MS = 5_000L
        private const val RECORDING_FRAME_WARN_LOG_INTERVAL_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val native = V2NativeRecordingBridge(nativeHandle)
    private val metrics = RecordingMetrics()
    private var recording = false
    private var generation = 0L
    private var writer: EncoderSegmentWriter? = null
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val segmentPrepareExecutor = Executors.newSingleThreadExecutor()
    private val timestampOverlay = TimestampOverlayRenderer(context, outputWidth, outputHeight)
    private val videoMimeType = if (h265Enabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    private val emergencyClips = V2EmergencyClipCoordinator(
        context = context,
        outputDir = outputDir,
        segmentDurationMs = segmentDurationMs,
        isRecording = { recording },
        stoppedWallClockMs = { stoppedWallClockMs },
    )
    @Volatile private var cleanupFuture: Future<V2StorageCleanupResult>? = null
    private var recordingSessionId = ""
    private var preparedSegmentIndex = -1
    private var preparedSegmentWallClockMs = 0L
    private var preparedSegmentFuture: Future<EncoderSegmentWriter>? = null
    private var segmentPrecreateEnabled = segmentPrecreateEnabled
    private var lastFramePerfLogMs = 0L
    private var lastFramePerfRequested = 0L
    private var lastFramePerfRendered = 0L
    private var lastFramePerfEncoded = 0L
    private var lastFramePerfDropped = 0L
    @Volatile private var stoppedWallClockMs = 0L

    fun start(): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "start size=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$recordingFps segmentMs=$segmentDurationMs precreate=$segmentPrecreateEnabled mime=$videoMimeType")
        metrics.apply {
            requestedFrames = 0; renderedFrames = 0; encodedSamples = 0; droppedFrames = 0
            segmentIndex = 0; segmentSwitchMs = 0; firstSampleLatencyMs = -1; lastError = "无"
        }
        scheduleStorageCleanup()
        recordingSessionId = createRecordingSessionId()
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
            writer?.let { releaseWriterAsync(it, finish = false, generateThumbnail = false) }
            writer = null
            V2AppLog.e("V2CompositeRecorder", "start failed", it)
            return false
        }
        scheduleRecordingTick(startGeneration, 0L)
        V2AppLog.perf("V2CompositeRecorder", "start", SystemClock.elapsedRealtime() - startedMs, "session=$recordingSessionId")
        return true
    }

    fun stop() {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "stop requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
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
                writerToStop?.requestDrain()
                if (writerToStop != null) {
                    runCatching {
                        if (native.renderCompositor()) {
                            metrics.renderedFrames += 1
                            writerToStop.requestDrain()
                        }
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
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true, generateThumbnail = true, segmentEndWallClockMs = stopWallClockMs)
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
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true, generateThumbnail = true, segmentEndWallClockMs = stopWallClockMs)
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
        V2AppLog.perf("V2CompositeRecorder", "stop_queue", SystemClock.elapsedRealtime() - startedMs)
    }

    fun stopBlockingForRelease(timeoutMs: Long = STOP_RENDER_TIMEOUT_MS + STOP_WRITER_TIMEOUT_MS) {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("V2CompositeRecorder", "stopBlockingForRelease requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
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
                writerToStop?.requestDrain()
                if (writerToStop != null) {
                    runCatching {
                        if (native.renderCompositor()) {
                            metrics.renderedFrames += 1
                            writerToStop.requestDrain()
                        }
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
        val finished = writerToStop?.finishAndReleaseBlocking(generateThumbnail = true, timeoutMs = STOP_WRITER_TIMEOUT_MS.coerceAtMost(timeoutMs))
        if (finished != null && fileSuffix.isEmpty()) {
            onSegmentFinalized(finished, writerToStop.mediaStartWallClockMs(), stopWallClockMs)
        }
        finishEmergencyClipExportsIfStopped()
        releasePreparedSegmentAsync()
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        segmentPrepareExecutor.shutdown()
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
        V2AppLog.d("V2CompositeRecorder", "start segment index=$segmentIndex preparedFuture=${preparedSegmentFuture != null} precreate=$segmentPrecreateEnabled")
        consumeFinishedCleanupResult()
        val newWriter = takePreparedSegment(segmentIndex, segmentWallClockMs)
        val oldWriter = writer
        if (newWriter == null && oldWriter != null) {
            runCatching { native.detachEncoderSurface() }
                .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface before sync segment switch failed", it) }
            releaseWriterAsync(oldWriter, finish = true, generateThumbnail = true, segmentEndWallClockMs = actualSegmentStartWallClockMs)
            writer = null
        }
        val segmentWriter = newWriter
            ?: EncoderSegmentWriter(context, outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate, recordingSessionId, fileSuffix, videoMimeType).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
            }
        val surface = segmentWriter.surface ?: throw IllegalStateException("Encoder surface unavailable")
        try {
            if (!native.isAvailable) throw java.lang.IllegalStateException(native.lastError())
            if (!native.attachEncoderSurface(surface)) throw java.lang.IllegalStateException(native.lastError())
        } catch (t: Throwable) {
            releaseWriterAsync(segmentWriter, finish = false, generateThumbnail = false)
            throw t
        }
        segmentWriter.markAttached(segmentIndex, actualSegmentStartWallClockMs)
        writer = segmentWriter
        if (newWriter != null) oldWriter?.let { releaseWriterAsync(it, finish = true, generateThumbnail = true, segmentEndWallClockMs = actualSegmentStartWallClockMs) }
        metrics.segmentIndex = segmentIndex
        V2AppLog.perf("V2CompositeRecorder", "segmentAttach", SystemClock.elapsedRealtime() - segmentStartMs, "index=$segmentIndex prepared=${newWriter != null} file=${segmentWriter.currentFile()?.name}")
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
            EncoderSegmentWriter(context, outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate, recordingSessionId, fileSuffix, videoMimeType).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
                V2AppLog.perf("V2CompositeRecorder", "prepareSegment", SystemClock.elapsedRealtime() - startedMs, "index=$segmentIndex file=${it.currentFile()?.name}")
            }
        }
    }

    private fun createRecordingSessionId(): String {
        val sessionTime = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(java.util.Date(System.currentTimeMillis()))
        val nonce = SystemClock.elapsedRealtimeNanos().toString(36)
        return "${sessionTime}_$nonce"
    }

    private fun takePreparedSegment(segmentIndex: Int, segmentWallClockMs: Long): EncoderSegmentWriter? {
        val future = preparedSegmentFuture ?: return null
        if (preparedSegmentIndex != segmentIndex || preparedSegmentWallClockMs != segmentWallClockMs) return null
        if (!future.isDone) {
            V2AppLog.w("V2CompositeRecorder", "prepared segment still busy at switch index=$segmentIndex; fall back for this segment")
            releasePreparedSegmentAsync()
            return null
        }
        val waitStartedMs = SystemClock.elapsedRealtime()
        return runCatching { future.get() }
            .onSuccess {
                preparedSegmentFuture = null
                preparedSegmentIndex = -1
                preparedSegmentWallClockMs = 0L
                V2AppLog.perf("V2CompositeRecorder", "takePreparedSegment", SystemClock.elapsedRealtime() - waitStartedMs, "index=$segmentIndex")
            }
            .onFailure {
                releasePreparedSegmentAsync()
                V2AppLog.w("V2CompositeRecorder", "prepared segment unavailable; fall back index=$segmentIndex waitMs=${SystemClock.elapsedRealtime() - waitStartedMs}", it)
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
                future.get().releaseBlocking(generateThumbnail = false)
            }.onFailure { V2AppLog.w("V2CompositeRecorder", "release prepared segment failed", it) }
        }
    }

    private fun scheduleRecordingTick(tickGeneration: Long, delayMs: Long) {
        renderHandler.postDelayed({ drawTick(tickGeneration) }, delayMs.coerceAtLeast(0L))
    }

    private fun drawTick(tickGeneration: Long) {
        if (!recording || tickGeneration != generation || writer == null) return
        try {
            val renderStartedMs = SystemClock.elapsedRealtime()
            metrics.requestedFrames += 1
            val wallClockMs = System.currentTimeMillis()
            timestampOverlay.updateIfNeeded(native, wallClockMs)
            val tick = native.recordingTickAndRender(wallClockMs)
            val renderMs = SystemClock.elapsedRealtime() - renderStartedMs
            if (tick < 0L) throw java.lang.IllegalStateException(native.lastError())
            val shouldRender = tick and TICK_SHOULD_RENDER != 0L
            val segmentDue = tick and TICK_SEGMENT_DUE != 0L
            val dropped = tick and TICK_DROPPED != 0L
            if (dropped) metrics.droppedFrames += 1

            if (shouldRender) {
                metrics.renderedFrames += 1
                writer?.requestDrain()
            }
            logRecordingFramePerfIfNeeded(renderMs, shouldRender, dropped)

            if (segmentDue) {
                val nextIndex = (tick ushr 32).toInt().coerceAtLeast(metrics.segmentIndex + 1)
                val switchStartedMs = SystemClock.elapsedRealtime()
                val segmentWallClockMs = native.beginNextSegment()
                runCatching { startNewSegment(nextIndex, segmentWallClockMs) }
                    .onSuccess { native.completeSegmentSwitch(true) }
                    .onFailure {
                        native.completeSegmentSwitch(false)
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
                val nextDelay = native.nextTickDelayMs().takeIf { it >= 0L } ?: (1000L / recordingFps.coerceAtLeast(1))
                scheduleRecordingTick(tickGeneration, nextDelay)
            }
        }
    }

    private class TimestampOverlayRenderer(
        context: Context,
        private val outputWidth: Int,
        private val outputHeight: Int,
    ) {
        companion object {
            private const val PREVIEW_DESIGN_WIDTH = 1280f
            private const val PREVIEW_DESIGN_HEIGHT = 800f
            private const val PREVIEW_LEFT_PANEL_WIDTH = 240f
            private const val PREVIEW_TIMESTAMP_SCREEN_X = 290f
            private const val PREVIEW_TIMESTAMP_Y = 40f
            private const val PREVIEW_TIMESTAMP_TEXT_SIZE = 28f
        }

        private val format = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
        private val widthScale = outputWidth / PREVIEW_DESIGN_WIDTH
        private val heightScale = outputHeight / PREVIEW_DESIGN_HEIGHT
        private val textScale = minOf(widthScale, heightScale)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT
            textSize = PREVIEW_TIMESTAMP_TEXT_SIZE * textScale
        }
        private val x = (PREVIEW_TIMESTAMP_SCREEN_X - PREVIEW_LEFT_PANEL_WIDTH) * widthScale
        private val y = PREVIEW_TIMESTAMP_Y * heightScale
        private val bitmap: Bitmap
        private val canvas: Canvas
        private var lastSecond = Long.MIN_VALUE

        init {
            val sampleText = "0000年00月00日 00:00:00"
            val fontMetrics = paint.fontMetrics
            val bitmapHeight = kotlin.math.ceil((fontMetrics.descent - fontMetrics.ascent).toDouble()).toInt().coerceAtLeast(1)
            val bitmapWidth = kotlin.math.ceil(paint.measureText(sampleText).toDouble()).toInt().coerceAtLeast(1)
            bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap)
        }

        fun updateIfNeeded(native: V2NativeRecordingBridge, wallClockMs: Long) {
            val second = wallClockMs / 1000L
            if (second == lastSecond) return
            lastSecond = second
            val text = format.format(Date(wallClockMs))
            renderIntoBitmap(text)
            if (!native.updateOverlayBitmap(bitmap, x, y)) {
                V2AppLog.w("V2CompositeRecorder", "update overlay bitmap failed: ${native.lastError()}")
            }
        }

        private fun renderIntoBitmap(text: String) {
            val fontMetrics = paint.fontMetrics
            bitmap.eraseColor(Color.TRANSPARENT)
            canvas.drawText(text, 0f, -fontMetrics.ascent, paint)
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

    private fun failStopOnRenderThread() {
        if (!recording) return
        val failureMessage = metrics.lastError
        recording = false
        generation += 1
        runCatching { native.stopSession() }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "stop native after render failure failed", it) }
        runCatching { native.detachEncoderSurface() }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder after render failure failed", it) }
        writer?.let { releaseWriterAsync(it, finish = false, generateThumbnail = false) }
        releasePreparedSegmentAsync()
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
        segmentPrepareExecutor.shutdown()
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

    private fun releaseWriterAsync(
        writer: EncoderSegmentWriter,
        finish: Boolean,
        generateThumbnail: Boolean,
        segmentEndWallClockMs: Long? = null,
    ) {
        releaseExecutor.execute {
            val startedMs = SystemClock.elapsedRealtime()
            V2AppLog.d("V2CompositeRecorder", "release writer finish=$finish thumbnail=$generateThumbnail file=${writer.currentFile()?.name}")
            val finished = if (finish) writer.finishAndReleaseBlocking(generateThumbnail = generateThumbnail)
            else writer.releaseBlocking(generateThumbnail = generateThumbnail)
            V2AppLog.perf("V2CompositeRecorder", "releaseWriter", SystemClock.elapsedRealtime() - startedMs, "finish=$finish thumbnail=$generateThumbnail file=${finished?.name ?: writer.currentFile()?.name}")
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
