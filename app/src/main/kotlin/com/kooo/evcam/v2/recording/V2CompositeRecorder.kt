package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.VulkanNative
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
    private val onFailure: (String) -> Unit = {},
) {
    companion object {
        private const val TICK_SHOULD_RENDER = 1L
        private const val TICK_DROPPED = 2L
        private const val TICK_SEGMENT_DUE = 4L
        private const val START_CAPTURE_TIMEOUT_MS = 2_000L
        private const val STOP_RENDER_TIMEOUT_MS = 2_000L
        private const val STOP_WRITER_TIMEOUT_MS = 2_500L
    }

    private data class EmergencyClipRequest(
        val startWallClockMs: Long,
        val endWallClockMs: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metrics = RecordingMetrics()
    private var recording = false
    private var generation = 0L
    private var writer: EncoderSegmentWriter? = null
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    private val segmentPrepareExecutor = Executors.newSingleThreadExecutor()
    private val eventClipExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cleanupFuture: Future<V2StorageCleaner.CleanupResult>? = null
    private var recordingSessionId = ""
    private var preparedSegmentIndex = -1
    private var preparedSegmentWallClockMs = 0L
    private var preparedSegmentFuture: Future<EncoderSegmentWriter>? = null
    private var segmentPrecreateEnabled = segmentPrecreateEnabled
    private val overlayTimeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
    private var lastOverlaySecond = Long.MIN_VALUE
    private val emergencyClipLock = Any()
    private val pendingEmergencyClips = mutableListOf<EmergencyClipRequest>()
    private val finalizedSegments = mutableListOf<V2EmergencyClipExtractor.SourceSegment>()
    @Volatile private var stoppedWallClockMs = 0L

    fun start(): Boolean {
        V2AppLog.i("V2CompositeRecorder", "start output=${outputDir.absolutePath} size=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$recordingFps segmentMs=$segmentDurationMs precreate=$segmentPrecreateEnabled")
        metrics.apply {
            requestedFrames = 0; renderedFrames = 0; encodedSamples = 0; droppedFrames = 0
            segmentIndex = 0; segmentSwitchMs = 0; firstSampleLatencyMs = -1; lastError = "无"
        }
        scheduleStorageCleanup()
        recordingSessionId = createRecordingSessionId()
        val startWallClockMs = System.currentTimeMillis()
        updateRecordingOverlay(startWallClockMs, force = true)
        val firstSegmentWallClockMs = VulkanNative.startRecordingSession(nativeHandle, recordingFps, segmentDurationMs, startWallClockMs)
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
            VulkanNative.stopRecordingSession(nativeHandle)
            runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                .onFailure { detachError -> V2AppLog.e("V2CompositeRecorder", "detach encoder after start failure failed", detachError) }
            writer?.let { releaseWriterAsync(it, finish = false, generateThumbnail = false) }
            writer = null
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
    }

    fun stopBlockingForRelease(timeoutMs: Long = STOP_RENDER_TIMEOUT_MS + STOP_WRITER_TIMEOUT_MS) {
        V2AppLog.i("V2CompositeRecorder", "stopBlockingForRelease requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
        VulkanNative.stopRecordingSession(nativeHandle)
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
                        if (VulkanNative.renderCompositor(nativeHandle)) {
                            metrics.renderedFrames += 1
                            writerToStop.requestDrain()
                        }
                    }.onFailure { t ->
                        metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "停止补帧失败")
                        V2AppLog.e("V2CompositeRecorder", "release final render failed", t)
                    }
                }
                runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
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
    }

    fun requestEmergencyClip(startWallClockMs: Long, durationMs: Long): Boolean {
        if (fileSuffix.isNotEmpty() || durationMs <= 0L) return false
        val request = EmergencyClipRequest(startWallClockMs, startWallClockMs + durationMs)
        synchronized(emergencyClipLock) { pendingEmergencyClips += request }
        V2AppLog.i("V2CompositeRecorder", "emergency clip requested start=$startWallClockMs durationMs=$durationMs")
        tryExportPendingEmergencyClips()
        return true
    }

    fun metricsSnapshot(): RecordingMetrics = metrics.copy()

    private fun startNewSegment(segmentIndex: Int, segmentWallClockMs: Long) {
        val segmentStartMs = SystemClock.elapsedRealtime()
        val actualSegmentStartWallClockMs = System.currentTimeMillis()
        V2AppLog.i("V2CompositeRecorder", "start segment index=$segmentIndex wallClockMs=$segmentWallClockMs preparedFuture=${preparedSegmentFuture != null} precreate=$segmentPrecreateEnabled")
        consumeFinishedCleanupResult()
        val newWriter = takePreparedSegment(segmentIndex, segmentWallClockMs)
        val oldWriter = writer
        if (newWriter == null && oldWriter != null) {
            runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface before sync segment switch failed", it) }
            releaseWriterAsync(oldWriter, finish = true, generateThumbnail = true, segmentEndWallClockMs = actualSegmentStartWallClockMs)
            writer = null
        }
        val segmentWriter = newWriter
            ?: EncoderSegmentWriter(context, outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate, recordingSessionId, fileSuffix).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
            }
        val surface = segmentWriter.surface ?: throw IllegalStateException("Encoder surface unavailable")
        try {
            if (nativeHandle == 0L) throw java.lang.IllegalStateException(VulkanNative.getLastError())
            if (!VulkanNative.attachEncoderSurface(nativeHandle, surface)) throw java.lang.IllegalStateException(VulkanNative.getLastError())
        } catch (t: Throwable) {
            releaseWriterAsync(segmentWriter, finish = false, generateThumbnail = false)
            throw t
        }
        segmentWriter.markAttached(segmentIndex, actualSegmentStartWallClockMs)
        writer = segmentWriter
        if (newWriter != null) oldWriter?.let { releaseWriterAsync(it, finish = true, generateThumbnail = true, segmentEndWallClockMs = actualSegmentStartWallClockMs) }
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
            EncoderSegmentWriter(context, outputDir, metrics, outputWidth, outputHeight, recordingFps, videoBitrate, recordingSessionId, fileSuffix).also {
                it.startSegment(segmentIndex, segmentWallClockMs)
                V2AppLog.i("V2CompositeRecorder", "prepared segment index=$segmentIndex file=${it.currentFile()?.name} prepareMs=${SystemClock.elapsedRealtime() - startedMs}")
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
                V2AppLog.i("V2CompositeRecorder", "prepared segment taken index=$segmentIndex waitMs=${SystemClock.elapsedRealtime() - waitStartedMs}")
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
            metrics.requestedFrames += 1
            val wallClockMs = System.currentTimeMillis()
            updateRecordingOverlay(wallClockMs, force = false)
            val tick = VulkanNative.recordingTickAndRender(nativeHandle, wallClockMs)
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

    private fun updateRecordingOverlay(wallClockMs: Long, force: Boolean) {
        val second = wallClockMs / 1000L
        if (!force && second == lastOverlaySecond) return
        val overlay = renderTimestampOverlay(wallClockMs)
        val uploaded = runCatching {
            VulkanNative.setRecordingOverlayBitmap(nativeHandle, overlay.rgba, overlay.width, overlay.height)
        }.getOrElse {
            V2AppLog.w("V2CompositeRecorder", "timestamp overlay upload failed", it)
            false
        }
        if (!uploaded) {
            V2AppLog.w("V2CompositeRecorder", "timestamp overlay upload rejected: ${VulkanNative.getLastError()}")
        }
        lastOverlaySecond = second
    }

    private fun renderTimestampOverlay(wallClockMs: Long): TimestampOverlayBitmap {
        val text = overlayTimeFormat.format(Date(wallClockMs))
        val scale = (outputHeight / 1600f).coerceIn(0.45f, 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = 38f * scale
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val metrics = paint.fontMetrics
        val width = kotlin.math.ceil(paint.measureText(text).toDouble()).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil((metrics.descent - metrics.ascent).toDouble()).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, -metrics.ascent, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val rgba = ByteArray(pixels.size * 4)
        var out = 0
        for (pixel in pixels) {
            rgba[out++] = Color.red(pixel).toByte()
            rgba[out++] = Color.green(pixel).toByte()
            rgba[out++] = Color.blue(pixel).toByte()
            rgba[out++] = Color.alpha(pixel).toByte()
        }
        return TimestampOverlayBitmap(rgba, width, height)
    }

    private data class TimestampOverlayBitmap(val rgba: ByteArray, val width: Int, val height: Int)

    private fun failStopOnRenderThread() {
        if (!recording) return
        val failureMessage = metrics.lastError
        recording = false
        generation += 1
        runCatching { VulkanNative.stopRecordingSession(nativeHandle) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "stop native after render failure failed", it) }
        runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
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

    private fun onSegmentFinalized(file: File, startWallClockMs: Long, endWallClockMs: Long) {
        if (startWallClockMs <= 0L || endWallClockMs <= startWallClockMs) return
        synchronized(emergencyClipLock) {
            finalizedSegments += V2EmergencyClipExtractor.SourceSegment(file, startWallClockMs, endWallClockMs)
            val oldestPendingStart = pendingEmergencyClips.minOfOrNull { it.startWallClockMs } ?: (System.currentTimeMillis() - segmentDurationMs * 2)
            finalizedSegments.removeAll { it.endWallClockMs < oldestPendingStart - segmentDurationMs }
        }
        V2AppLog.i("V2CompositeRecorder", "normal segment finalized for emergency clips file=${file.name} start=$startWallClockMs end=$endWallClockMs")
        tryExportPendingEmergencyClips()
    }

    private fun tryExportPendingEmergencyClips() {
        data class ReadyClip(val request: EmergencyClipRequest, val exportEndWallClockMs: Long, val sources: List<V2EmergencyClipExtractor.SourceSegment>)
        val ready = synchronized(emergencyClipLock) {
            val stoppedAt = stoppedWallClockMs
            val readyRequests = pendingEmergencyClips.mapNotNull { request ->
                val exportEnd = if (stoppedAt > 0L && request.endWallClockMs > stoppedAt) stoppedAt else request.endWallClockMs
                if (exportEnd <= request.startWallClockMs) return@mapNotNull null
                if (finalizedSegments.any { it.startWallClockMs <= request.startWallClockMs && it.endWallClockMs > request.startWallClockMs } &&
                    finalizedSegments.any { it.startWallClockMs < exportEnd && it.endWallClockMs >= exportEnd }) {
                    ReadyClip(request, exportEnd, finalizedSegments.filter { it.endWallClockMs > request.startWallClockMs && it.startWallClockMs < exportEnd })
                } else {
                    null
                }
            }
            pendingEmergencyClips.removeAll(readyRequests.map { it.request }.toSet())
            readyRequests
        }
        for (clip in ready) {
            eventClipExecutor.execute {
                V2EmergencyClipExtractor.extract(
                    context = context.applicationContext,
                    outputDir = outputDir,
                    clipStartWallClockMs = clip.request.startWallClockMs,
                    clipEndWallClockMs = clip.exportEndWallClockMs,
                    sources = clip.sources,
                )
            }
        }
    }

    private fun releaseWriterAsync(
        writer: EncoderSegmentWriter,
        finish: Boolean,
        generateThumbnail: Boolean,
        segmentEndWallClockMs: Long? = null,
    ) {
        releaseExecutor.execute {
            V2AppLog.i("V2CompositeRecorder", "release writer finish=$finish thumbnail=$generateThumbnail file=${writer.currentFile()?.name}")
            val finished = if (finish) writer.finishAndReleaseBlocking(generateThumbnail = generateThumbnail)
            else writer.releaseBlocking(generateThumbnail = generateThumbnail)
            if (finish && finished != null && fileSuffix.isEmpty() && segmentEndWallClockMs != null) {
                onSegmentFinalized(finished, writer.mediaStartWallClockMs(), segmentEndWallClockMs)
            }
            finishEmergencyClipExportsIfStopped()
        }
    }

    private fun finishEmergencyClipExportsIfStopped() {
        if (recording || stoppedWallClockMs <= 0L) return
        synchronized(emergencyClipLock) {
            val dropped = pendingEmergencyClips.size
            if (dropped > 0) {
                V2AppLog.w("V2CompositeRecorder", "drop uncovered emergency clip requests on stop count=$dropped")
                pendingEmergencyClips.clear()
            }
        }
        eventClipExecutor.shutdown()
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
