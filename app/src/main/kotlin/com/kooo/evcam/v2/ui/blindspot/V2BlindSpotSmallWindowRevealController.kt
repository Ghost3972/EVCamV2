package com.kooo.evcam.v2.ui.blindspot

import android.os.Handler
import android.view.Window
import com.kooo.evcam.v2.log.V2AppLog

internal class V2BlindSpotSmallWindowRevealController(
    private val window: Window,
    private val handler: Handler,
    private val isClosed: () -> Boolean,
    private val currentTarget: () -> V2BlindSpotSmallWindowTarget,
    private val afterReveal: (String) -> Unit,
) {
    var revealed: Boolean = false
        private set

    private val fallbackRunnable = Runnable {
        if (!isClosed()) reveal("timeout")
    }

    fun scheduleFallback() {
        handler.removeCallbacks(fallbackRunnable)
        handler.postDelayed(fallbackRunnable, REVEAL_FALLBACK_MS)
    }

    fun cancel() {
        handler.removeCallbacks(fallbackRunnable)
    }

    fun resetAndHide() {
        revealed = false
        window.attributes = window.attributes.apply { alpha = 0f }
        scheduleFallback()
    }

    fun markFrameLost() {
        revealed = false
    }

    fun reveal(reason: String) {
        if (revealed && window.attributes.alpha == 1f) return
        revealed = true
        window.attributes = window.attributes.apply { alpha = 1f }
        afterReveal(reason)
        val target = currentTarget()
        V2AppLog.i(TAG, "reveal small window reason=$reason side=${target.side} index=${target.cameraIndex}")
    }

    private companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
        private const val REVEAL_FALLBACK_MS = 2_000L
    }
}
