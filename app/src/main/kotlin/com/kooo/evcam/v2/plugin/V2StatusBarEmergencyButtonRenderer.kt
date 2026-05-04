package com.kooo.evcam.v2.plugin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.TextView
import com.kooo.evcam.R

internal class V2StatusBarEmergencyButtonRenderer {
    private var progressBackground: LayerDrawable? = null
    private var progressClip: ClipDrawable? = null
    private var lastText: String? = null
    private var lastActive = false
    private var lastProgressSecond = -1

    fun render(button: TextView?, emergency: Boolean, emergencyEndsAtMs: Long) {
        button ?: return
        button.isSelected = emergency
        if (!emergency) {
            setText(button, "紧急录制")
            if (lastActive) button.setBackgroundResource(R.drawable.v2_status_bar_button_bg)
            lastActive = false
            lastProgressSecond = -1
            return
        }

        val now = System.currentTimeMillis()
        val remainingMs = (emergencyEndsAtMs - now).coerceAtLeast(0L)
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
        setText(button, if (remainingSeconds > 0) "紧急录制中 ${remainingSeconds}s" else "紧急录制中")
        if (!lastActive || progressBackground == null) {
            button.background = createProgressBackground(button.context)
        }
        if (remainingSeconds != lastProgressSecond) {
            val elapsedSeconds = (EMERGENCY_DURATION_SECONDS - remainingSeconds).coerceIn(0, EMERGENCY_DURATION_SECONDS)
            progressClip?.level = (elapsedSeconds * 10_000) / EMERGENCY_DURATION_SECONDS
            lastProgressSecond = remainingSeconds
        }
        lastActive = true
    }

    private fun setText(button: TextView, text: String) {
        if (lastText == text) return
        button.text = text
        lastText = text
    }

    private fun createProgressBackground(context: Context): LayerDrawable {
        val radius = 4f * context.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            setColor(Color.parseColor("#D9FFFFFF"))
            cornerRadius = radius
        }
        val progress = GradientDrawable().apply {
            setColor(Color.parseColor("#B3D50000"))
            cornerRadius = radius
        }
        val clip = ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)
        progressClip = clip
        return LayerDrawable(arrayOf(base, clip)).also { progressBackground = it }
    }

    private companion object {
        private const val EMERGENCY_DURATION_SECONDS = 15
    }
}
