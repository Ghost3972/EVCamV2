package com.kooo.evcam.v2.service.status

import android.content.Context
import android.os.SystemClock
import android.widget.Toast

internal class V2ToastDebouncer(
    private val context: Context,
) {
    private var lastToastText: String? = null
    private var lastToastMs = 0L

    fun show(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastToastText && now - lastToastMs < TOAST_DEBOUNCE_MS) return
        lastToastText = message
        lastToastMs = now
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val TOAST_DEBOUNCE_MS = 5_000L
    }
}
