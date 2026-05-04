package com.kooo.evcam.v2.ui.playback

import java.util.Locale

internal object V2PlaybackTimeFormatter {
    fun formatDuration(ms: Int): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
