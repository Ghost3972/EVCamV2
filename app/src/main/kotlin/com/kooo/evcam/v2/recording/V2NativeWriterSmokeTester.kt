package com.kooo.evcam.v2.recording

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaFormat
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Developer-only smoke test for the off-by-default native AMediaCodec/AMediaMuxer writer.
 *
 * This does not switch normal recording to the native backend. It creates one native writer,
 * draws synthetic Canvas frames into its input surface, drains it, and finalizes one MP4 file.
 */
object V2NativeWriterSmokeTester {
    private const val TAG = "V2NativeWriterSmoke"
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS = 15
    private const val DEFAULT_BITRATE = 4_000_000
    private const val DEFAULT_DURATION_MS = 10_000L

    @Volatile private var running: AtomicBoolean? = null
    @Volatile private var runningFuture: Future<*>? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "V2NativeWriterSmoke").apply { isDaemon = true }
    }

    data class Config(
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
        val fps: Int = DEFAULT_FPS,
        val bitrate: Int = DEFAULT_BITRATE,
        val durationMs: Long = DEFAULT_DURATION_MS,
        val h265: Boolean = false,
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
        width = config.width.coerceAtLeast(64),
        height = config.height.coerceAtLeast(64),
        fps = config.fps.coerceIn(1, 60),
        bitrate = config.bitrate.coerceAtLeast(100_000),
        durationMs = config.durationMs.coerceIn(1_000L, 120_000L),
    )

    private fun run(context: Context, config: Config, running: AtomicBoolean) {
        val startedMs = SystemClock.elapsedRealtime()
        val mime = if (config.h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val outputDir = File(V2StoragePathHelper.outputDir(context), "native_writer_smoke").apply { mkdirs() }
        val metrics = RecordingMetrics()
        val writer = V2SegmentWriterFactory.create(
            backend = V2RecordingBackend.NativeExperimental,
            config = V2SegmentWriterConfig(
                context = context,
                outputDir = outputDir,
                metrics = metrics,
                width = config.width,
                height = config.height,
                fps = config.fps,
                bitrate = config.bitrate,
                recordingSessionId = "native-smoke-${System.currentTimeMillis()}",
                fileSuffix = "_native_smoke",
                mimeType = mime,
            ),
        )
        var outputFile: File? = null
        var frames = 0L
        try {
            outputFile = writer.startSegment(segmentIndex = 0, segmentWallClockMs = System.currentTimeMillis())
            writer.markAttached(segmentIndex = 0)
            V2AppLog.i(TAG, "started size=${config.width}x${config.height} fps=${config.fps} bitrate=${config.bitrate} mime=$mime file=${outputFile.name}")
            val surface = checkNotNull(writer.surface) { "native writer input surface unavailable" }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
            val frameIntervalMs = (1000L / config.fps).coerceAtLeast(1L)
            val endAtMs = SystemClock.elapsedRealtime() + config.durationMs
            while (running.get() && SystemClock.elapsedRealtime() < endAtMs) {
                val frameStartMs = SystemClock.elapsedRealtime()
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.rgb((40 + frames * 3 % 180).toInt(), 80, 150))
                    paint.color = Color.WHITE
                    canvas.drawText("EVCam native writer smoke", 48f, 96f, paint)
                    canvas.drawText("frame=$frames", 48f, 168f, paint)
                    canvas.drawText("${config.width}x${config.height}@${config.fps} ${String.format(Locale.US, "%.1f", config.bitrate / 1_000_000f)}Mbps", 48f, 240f, paint)
                    paint.color = Color.YELLOW
                    canvas.drawCircle(80f + (frames % (config.width - 160).coerceAtLeast(1)).toFloat(), config.height * 0.68f, 36f, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                frames += 1
                writer.requestDrain()
                val sleepMs = frameIntervalMs - (SystemClock.elapsedRealtime() - frameStartMs)
                if (sleepMs > 0) SystemClock.sleep(sleepMs)
            }
        } catch (t: Throwable) {
            metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "native smoke failed")
            V2AppLog.e(TAG, "run failed", t)
        } finally {
            val finishStartedMs = SystemClock.elapsedRealtime()
            val finishedFile = runCatching { writer.finishAndReleaseBlocking(generateThumbnail = false, timeoutMs = 10_000L) }
                .onFailure { V2AppLog.e(TAG, "finish failed", it) }
                .getOrNull()
            V2AppLog.perf(
                TAG,
                "finish",
                SystemClock.elapsedRealtime() - finishStartedMs,
                "frames=$frames file=${finishedFile?.absolutePath ?: outputFile?.absolutePath ?: "none"} bytes=${finishedFile?.length() ?: outputFile?.length() ?: 0L} error=${metrics.lastError ?: "none"}",
            )
            V2AppLog.perf(TAG, "total", SystemClock.elapsedRealtime() - startedMs, "frames=$frames")
        }
    }
}
