package com.kooo.evcam.v2.ui.blindspot

import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog
import java.util.Locale

internal class V2BlindSpotOverlayMetrics(
    private val renderedFrames: (Int) -> Long,
) {
    private var displayedFrameCount = 0L
    private var lastFpsFrames = 0L
    private var fpsWindowStartedMs = 0L
    private var lastFpsPerfLogMs = 0L

    fun onFrameDisplayed() {
        displayedFrameCount += 1
    }

    fun reset(params: WindowManager.LayoutParams?, metricsView: TextView?) {
        displayedFrameCount = 0L
        lastFpsFrames = 0L
        fpsWindowStartedMs = SystemClock.elapsedRealtime()
        lastFpsPerfLogMs = 0L
        updatePlaceholder(params, metricsView)
    }

    fun updateText(
        params: WindowManager.LayoutParams?,
        cameraIndex: Int,
        metricsView: TextView?,
        fps: Float? = null,
    ) {
        params ?: return
        val value = fps ?: sampleFps(cameraIndex) ?: return
        metricsView?.text = String.format(Locale.US, "%dx%d\n%.1f fps", params.width, params.height, value)
        logFpsIfNeeded(cameraIndex, value, params.width, params.height)
    }

    private fun updatePlaceholder(params: WindowManager.LayoutParams?, metricsView: TextView?) {
        params ?: return
        metricsView?.text = String.format(Locale.US, "%dx%d\n-- fps", params.width, params.height)
    }

    private fun sampleFps(cameraIndex: Int): Float? {
        if (cameraIndex < 0) return null
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - fpsWindowStartedMs
        if (elapsed <= 0L || elapsed < FPS_MIN_SAMPLE_MS) return null
        val frames = displayedFrameCount
        if (frames == 0L && elapsed < FPS_STARTUP_GRACE_MS) return null
        val currentFps = (frames - lastFpsFrames).coerceAtLeast(0L) * 1000f / elapsed
        lastFpsFrames = frames
        fpsWindowStartedMs = now
        return currentFps
    }

    private fun logFpsIfNeeded(index: Int, fps: Float, width: Int, height: Int) {
        if (index < 0) return
        val now = SystemClock.elapsedRealtime()
        if (fps >= LOW_FPS_THRESHOLD && now - lastFpsPerfLogMs < FPS_PERF_LOG_INTERVAL_MS) return
        lastFpsPerfLogMs = now
        V2AppLog.perf(
            "V2BlindSpotPerf",
            if (fps < LOW_FPS_THRESHOLD) "overlayFps_low" else "overlayFps",
            0L,
            "index=$index fps=${String.format(Locale.US, "%.1f", fps)} window=${width}x$height displayed=$displayedFrameCount nativeRendered=${renderedFrames(index)}",
        )
    }

    companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        private const val FPS_MIN_SAMPLE_MS = 750L
        private const val FPS_STARTUP_GRACE_MS = 1_500L
        private const val FPS_PERF_LOG_INTERVAL_MS = 5_000L
        private const val LOW_FPS_THRESHOLD = 18f
    }
}
