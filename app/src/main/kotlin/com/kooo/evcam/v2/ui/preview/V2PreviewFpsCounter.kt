package com.kooo.evcam.v2.ui.preview

import android.os.SystemClock
import kotlin.math.roundToInt

internal class V2PreviewFpsCounter {
    private var frames = 0
    private var startedMs = 0L

    fun reset() {
        frames = 0
        startedMs = SystemClock.elapsedRealtime()
    }

    fun onFrame(): Int? {
        val now = SystemClock.elapsedRealtime()
        if (startedMs == 0L) startedMs = now
        frames += 1
        val elapsed = now - startedMs
        if (elapsed < 1_000L) return null
        val value = ((frames * 1000f) / elapsed).roundToInt().coerceAtLeast(1)
        frames = 0
        startedMs = now
        return value
    }
}
