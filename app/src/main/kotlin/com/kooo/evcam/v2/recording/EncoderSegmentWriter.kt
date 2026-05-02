package com.kooo.evcam.v2.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EncoderSegmentWriter(
    context: Context,
    private val outputDir: File,
    private val metrics: RecordingMetrics,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val recordingSessionId: String,
    private val fileSuffix: String = "",
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
    private companion object {
        private const val SLOW_WRITE_MS = 8L
    }

    private val appContext = context.applicationContext
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: android.view.Surface? = null
    private var persistentInputSurface = false
    private var trackIndex = -1
    private var muxerStarted = false
    private var writtenSamples = 0L
    private var segmentStartedAtMs = 0L
    private var currentFile: File? = null
    private var tempFile: File? = null
    private var segmentWallClockMs = 0L
    private var attachedWallClockMs = 0L
    private val drainExecutor = Executors.newSingleThreadExecutor()
    private val drainPending = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var finishing = false
    private var lastDrainPerfLogMs = 0L

    val surface: android.view.Surface? get() = inputSurface

    fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i("EncoderSegmentWriter", "startSegment index=$segmentIndex size=${width}x${height} fps=$fps bitrate=$bitrate mime=$mimeType")
        releaseInternal(generateThumbnail = false)
        finishing = false
        drainPending.set(false)
        segmentStartedAtMs = SystemClock.elapsedRealtime()
        this.segmentWallClockMs = segmentWallClockMs
        val formatStamp = V2SegmentFileNamer.timestamp(segmentWallClockMs)
        currentFile = V2SegmentFileNamer.uniqueFile(outputDir, formatStamp, fileSuffix)
        tempFile = File(outputDir, currentFile!!.name + ".recording")
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        codec = MediaCodec.createEncoderByType(mimeType).apply {
            V2AppLog.i("EncoderSegmentWriter", "selected encoder codec=$name mime=$mimeType")
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createPersistentInputSurfaceOrNull()?.let { persistentSurface ->
                runCatching {
                    setInputSurface(persistentSurface)
                    persistentInputSurface = true
                    V2AppLog.i("EncoderSegmentWriter", "using persistent input surface codec=$name")
                    persistentSurface
                }.getOrElse {
                    runCatching { persistentSurface.release() }
                    persistentInputSurface = false
                    V2AppLog.w("EncoderSegmentWriter", "set persistent input surface failed; fallback to regular surface codec=$name", it)
                    createInputSurface()
                }
            } ?: createInputSurface().also {
                persistentInputSurface = false
                V2AppLog.w("EncoderSegmentWriter", "using regular input surface codec=$name")
            }
            start()
        }
        muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        writtenSamples = 0L
        V2AppLog.perf("EncoderSegmentWriter", "startSegment", SystemClock.elapsedRealtime() - startedMs, "index=$segmentIndex temp=${tempFile?.name} final=${currentFile?.name} persistentSurface=$persistentInputSurface")
        return currentFile!!
    }

    fun markAttached(segmentIndex: Int, attachedWallClockMs: Long = System.currentTimeMillis()) {
        metrics.segmentIndex = segmentIndex
        segmentStartedAtMs = SystemClock.elapsedRealtime()
        this.attachedWallClockMs = attachedWallClockMs
    }

    fun requestDrain() {
        if (finishing) return
        if (!drainPending.compareAndSet(false, true)) return
        drainExecutor.execute {
            try {
                drainInternal(false)
            } catch (t: Throwable) {
                metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "drain failed")
                V2AppLog.e("EncoderSegmentWriter", "async drain failed file=${currentFile?.name}", t)
            } finally {
                drainPending.set(false)
            }
        }
    }

    private fun drainInternal(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val startedMs = SystemClock.elapsedRealtime()
        var drainedSamples = 0L
        var writeMs = 0L
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) {
                    logDrainCost(startedMs, drainedSamples, writeMs, endOfStream)
                    return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)
                    val codecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && bufferInfo.size > 0 && muxerStarted && !codecConfig) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        val writeStartedMs = SystemClock.elapsedRealtime()
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        val sampleWriteMs = SystemClock.elapsedRealtime() - writeStartedMs
                        writeMs += sampleWriteMs
                        writtenSamples += 1
                        drainedSamples += 1
                        metrics.encodedSamples += 1
                        if (metrics.firstSampleLatencyMs < 0) {
                            metrics.firstSampleLatencyMs = SystemClock.elapsedRealtime() - segmentStartedAtMs
                            V2AppLog.perf("EncoderSegmentWriter", "firstSample", metrics.firstSampleLatencyMs, "file=${currentFile?.name} size=${bufferInfo.size}")
                        }
                        if (sampleWriteMs >= SLOW_WRITE_MS) {
                            V2AppLog.perf("EncoderSegmentWriter", "sampleWrite_slow", sampleWriteMs, "file=${currentFile?.name} sample=$writtenSamples size=${bufferInfo.size}")
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        logDrainCost(startedMs, drainedSamples, writeMs, endOfStream)
                        return
                    }
                }
            }
        }
    }

    private fun logDrainCost(startedMs: Long, samples: Long, writeMs: Long, endOfStream: Boolean) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedMs
        val now = SystemClock.elapsedRealtime()
        if (elapsedMs >= 8L || writeMs >= 4L || samples >= 4L || endOfStream || (samples > 0L && now - lastDrainPerfLogMs >= 3_000L)) {
            lastDrainPerfLogMs = now
            V2AppLog.perf(
                "EncoderSegmentWriter",
                "drain",
                elapsedMs,
                "samples=$samples writeMs=$writeMs eos=$endOfStream totalSamples=$writtenSamples file=${currentFile?.name}"
            )
        }
    }

    private fun createPersistentInputSurfaceOrNull(): android.view.Surface? {
        return runCatching { MediaCodec.createPersistentInputSurface() }
            .onFailure { V2AppLog.w("EncoderSegmentWriter", "create persistent input surface failed; fallback to regular surface", it) }
            .getOrNull()
    }

    fun finishAndReleaseBlocking(generateThumbnail: Boolean = true, timeoutMs: Long = 10_000L): File? {
        finishing = true
        val latch = CountDownLatch(1)
        val result = AtomicReference<File?>()
        drainExecutor.execute {
            try {
                val eosSignaled = runCatching { codec?.signalEndOfInputStream() }
                    .onFailure { V2AppLog.e("EncoderSegmentWriter", "signal EOS failed file=${currentFile?.name}", it) }
                    .isSuccess
                runCatching { drainInternal(eosSignaled) }
                    .onFailure { V2AppLog.e("EncoderSegmentWriter", "final drain failed file=${currentFile?.name}", it) }
                result.set(releaseInternal(generateThumbnail))
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        drainExecutor.shutdown()
        return result.get()
    }

    fun releaseBlocking(generateThumbnail: Boolean = false, timeoutMs: Long = 1500L): File? {
        finishing = true
        val latch = CountDownLatch(1)
        val result = AtomicReference<File?>()
        drainExecutor.execute {
            try {
                result.set(releaseInternal(generateThumbnail))
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        drainExecutor.shutdown()
        return result.get()
    }

    private fun releaseInternal(generateThumbnail: Boolean): File? {
        val startedMs = SystemClock.elapsedRealtime()
        val muxerStopOk = if (muxerStarted && writtenSamples > 0L) {
            runCatching { muxer?.stop() }
                .onFailure {
                    metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "muxer stop failed")
                    V2AppLog.e("EncoderSegmentWriter", "muxer stop failed file=${currentFile?.name} samples=$writtenSamples", it)
                }
                .isSuccess
        } else {
            false
        }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        persistentInputSurface = false
        val finishedFile = finalizeTempFile(muxerStopOk)
        V2AppLog.perf("EncoderSegmentWriter", "releaseInternal", SystemClock.elapsedRealtime() - startedMs, "file=${currentFile?.name} temp=${tempFile?.name} muxerStopOk=$muxerStopOk samples=$writtenSamples final=${finishedFile?.name}")
        finishedFile?.let { file ->
            V2PlaybackListCache.upsertVideo(appContext, file)
            if (generateThumbnail) V2RecordingThumbnailer.generateFirstFrameAsync(appContext, file)
        }
        return finishedFile
    }

    fun currentFile(): File? = currentFile
    fun segmentWallClockMs(): Long = segmentWallClockMs
    fun mediaStartWallClockMs(): Long = attachedWallClockMs.takeIf { it > 0L } ?: segmentWallClockMs
    fun currentSizeBytes(): Long = tempFile?.takeIf { it.exists() }?.length() ?: currentFile?.takeIf { it.exists() }?.length() ?: 0L

    private fun finalizeTempFile(muxerStopOk: Boolean): File? {
        val startedMs = SystemClock.elapsedRealtime()
        val temp = tempFile ?: return currentFile?.takeIf { it.exists() && it.length() > 0L }
        val final = currentFile ?: return null
        if (!muxerStopOk || writtenSamples <= 0L || !temp.exists() || temp.length() <= 0L) {
            V2AppLog.w("EncoderSegmentWriter", "drop temp segment muxerStopOk=$muxerStopOk samples=$writtenSamples exists=${temp.exists()} size=${temp.length()} temp=${temp.name}")
            runCatching { temp.delete() }
            return null
        }
        final.delete()
        return if (temp.renameTo(final)) {
            V2AppLog.perf("EncoderSegmentWriter", "finalizeTempFile", SystemClock.elapsedRealtime() - startedMs, "file=${final.name} size=${final.length()}")
            final
        } else {
            V2AppLog.w("EncoderSegmentWriter", "segment rename failed, keep temp=${temp.absolutePath}")
            temp
        }
    }
}
