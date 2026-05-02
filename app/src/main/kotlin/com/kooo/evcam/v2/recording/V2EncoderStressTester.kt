package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Developer-only encoder pressure test.
 *
 * This validates whether the device can keep four hardware video encoders alive,
 * draining and writing MP4 files at the requested resolution/fps/bitrate. Frames
 * are synthetic Canvas frames, so this tests encoder + muxer + storage pressure,
 * not the Camera2 multi-target path.
 */
object V2EncoderStressTester {
    private const val TAG = "V2EncoderStress"
    private const val DEFAULT_LANES = 4
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS = 15
    private const val DEFAULT_BITRATE = 4_000_000
    private const val DEFAULT_DURATION_MS = 60_000L
    private const val FINISH_TIMEOUT_MS = 5_000L

    @Volatile private var running: AtomicBoolean? = null
    @Volatile private var runningFuture: Future<*>? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "V2EncoderStress").apply { isDaemon = true }
    }

    data class Config(
        val lanes: Int = DEFAULT_LANES,
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
        val fps: Int = DEFAULT_FPS,
        val bitrate: Int = DEFAULT_BITRATE,
        val durationMs: Long = DEFAULT_DURATION_MS,
        val h265: Boolean = true,
    )

    fun start(context: Context, config: Config = Config()): Boolean {
        val current = running
        if (current != null && current.get()) {
            V2AppLog.w(TAG, "start ignored: already running")
            return false
        }
        val flag = AtomicBoolean(true)
        running = flag
        val appContext = context.applicationContext
        runningFuture = executor.submit {
            try {
                run(appContext, normalized(config), flag)
            } finally {
                flag.set(false)
                if (running === flag) running = null
            }
        }
        return true
    }

    fun stop() {
        running?.set(false)
        runningFuture?.cancel(false)
        V2AppLog.i(TAG, "stop requested")
    }

    private fun normalized(config: Config): Config = config.copy(
        lanes = config.lanes.coerceIn(1, 8),
        width = config.width.coerceAtLeast(64),
        height = config.height.coerceAtLeast(64),
        fps = config.fps.coerceIn(1, 60),
        bitrate = config.bitrate.coerceAtLeast(100_000),
        durationMs = config.durationMs.coerceIn(1_000L, 10 * 60_000L),
    )

    private fun run(context: Context, config: Config, running: AtomicBoolean) {
        val startedMs = SystemClock.elapsedRealtime()
        val mime = if (config.h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val outputDir = File(V2StoragePathHelper.outputDir(context), "encoder_stress").apply { mkdirs() }
        V2AppLog.i(
            TAG,
            "begin lanes=${config.lanes} size=${config.width}x${config.height} fps=${config.fps} bitrate=${config.bitrate} durationMs=${config.durationMs} mime=$mime dir=${outputDir.absolutePath}"
        )

        val lanes = mutableListOf<Lane>()
        try {
            repeat(config.lanes) { index ->
                lanes += Lane(index, context, outputDir, config, mime).also { it.start() }
            }
            V2AppLog.i(TAG, "all lanes started count=${lanes.size}")

            val frameIntervalMs = (1000L / config.fps).coerceAtLeast(1L)
            val endAtMs = SystemClock.elapsedRealtime() + config.durationMs
            var frameIndex = 0L
            while (running.get() && SystemClock.elapsedRealtime() < endAtMs) {
                val frameStartedMs = SystemClock.elapsedRealtime()
                lanes.forEach { lane -> lane.drawAndDrain(frameIndex) }
                frameIndex += 1
                val sleepMs = frameIntervalMs - (SystemClock.elapsedRealtime() - frameStartedMs)
                if (sleepMs > 0) SystemClock.sleep(sleepMs)
            }
        } catch (t: Throwable) {
            V2AppLog.e(TAG, "run failed", t)
        } finally {
            val finishStartedMs = SystemClock.elapsedRealtime()
            lanes.forEach { lane -> lane.finishAndRelease() }
            V2AppLog.perf(
                TAG,
                "finish",
                SystemClock.elapsedRealtime() - finishStartedMs,
                lanes.joinToString { lane -> "lane=${lane.index} frames=${lane.frames} samples=${lane.samples} bytes=${lane.outputFile.length()} err=${lane.lastError ?: "none"}" }
            )
            V2AppLog.perf(TAG, "total", SystemClock.elapsedRealtime() - startedMs, "lanes=${lanes.size}")
        }
    }

    private class Lane(
        val index: Int,
        private val context: Context,
        private val outputDir: File,
        private val config: Config,
        private val mime: String,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
        }
        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var inputSurface: Surface? = null
        private var muxerStarted = false
        private var trackIndex = -1
        private var startedAtMs = 0L
        private var firstSampleLatencyMs = -1L
        var frames = 0L
            private set
        var samples = 0L
            private set
        var lastError: String? = null
            private set
        lateinit var outputFile: File
            private set

        fun start() {
            startedAtMs = SystemClock.elapsedRealtime()
            val stamp = V2SegmentFileNamer.timestamp(System.currentTimeMillis())
            outputFile = V2SegmentFileNamer.uniqueFile(outputDir, stamp, "_stress_${index}")
            val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            codec = MediaCodec.createEncoderByType(mime).apply {
                V2AppLog.i(TAG, "lane=$index selected codec=$name mime=$mime file=${outputFile.name}")
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun drawAndDrain(frameIndex: Long) {
            val surface = inputSurface ?: return
            runCatching {
                val canvas = surface.lockCanvas(null)
                try {
                    val baseColor = Color.rgb((60 + index * 40) % 255, (90 + index * 55) % 255, (130 + index * 70) % 255)
                    canvas.drawColor(baseColor)
                    paint.color = Color.WHITE
                    canvas.drawText("EVCam encoder stress", 48f, 96f, paint)
                    canvas.drawText("lane=$index frame=$frameIndex", 48f, 168f, paint)
                    canvas.drawText("${config.width}x${config.height}@${config.fps} ${config.bitrate / 1_000_000f}Mbps", 48f, 240f, paint)
                    paint.color = Color.YELLOW
                    canvas.drawCircle(
                        80f + (frameIndex % (config.width - 160).coerceAtLeast(1)).toFloat(),
                        (config.height * 0.65f),
                        36f,
                        paint,
                    )
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                frames += 1
            }.onFailure {
                lastError = it.javaClass.simpleName + ": " + (it.message ?: "draw failed")
                V2AppLog.e(TAG, "lane=$index draw failed", it)
            }
            drain(endOfStream = false)
        }

        fun finishAndRelease() {
            runCatching { codec?.signalEndOfInputStream() }
                .onFailure { V2AppLog.w(TAG, "lane=$index signal EOS failed", it) }
            val drainUntilMs = SystemClock.elapsedRealtime() + FINISH_TIMEOUT_MS
            do {
                val done = drain(endOfStream = true)
                if (done) break
            } while (SystemClock.elapsedRealtime() < drainUntilMs)

            runCatching { if (muxerStarted) muxer?.stop() }
                .onFailure { V2AppLog.e(TAG, "lane=$index muxer stop failed samples=$samples", it) }
            runCatching { muxer?.release() }
            muxer = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            V2AppLog.i(
                TAG,
                "lane=$index finished file=${outputFile.name} size=${outputFile.length()} frames=$frames samples=$samples firstSampleMs=$firstSampleLatencyMs"
            )
        }

        private fun drain(endOfStream: Boolean): Boolean {
            val codec = codec ?: return true
            val muxer = muxer ?: return true
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
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
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                            samples += 1
                            if (firstSampleLatencyMs < 0) {
                                firstSampleLatencyMs = SystemClock.elapsedRealtime() - startedAtMs
                                V2AppLog.perf(TAG, "laneFirstSample", firstSampleLatencyMs, "lane=$index size=${bufferInfo.size}")
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
        }
    }
}
