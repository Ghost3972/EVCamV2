package com.kooo.evcam.v2.ui.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import com.kooo.evcam.databinding.ActivityV2MainA7Binding

internal class V2MainRecordingUiController(
    private val binding: ActivityV2MainA7Binding,
    private val mainHandler: Handler,
    private val emergencyRecordingDurationMs: Long,
    private val onNormalRecordingChanged: (Boolean) -> Unit,
) {
    private var normalRecordingAnimator: ObjectAnimator? = null
    private var emergencyProgressAnimator: ValueAnimator? = null
    private var recordingDotBlinking = false
    private var recordingDotVisible = true
    private var normalRecording = false
    private var emergencyRecording = false
    private var emergencyRecordingEndsAtMs = 0L
    private var lastEmergencyRepeatToastMs = 0L

    private val emergencyRecordingTicker = object : Runnable {
        override fun run() {
            updateRecordingPillText()
            if (emergencyRecording) mainHandler.postDelayed(this, 250L)
        }
    }

    private val recordingDotBlinker = object : Runnable {
        override fun run() {
            if (binding.tvRecordingPill.visibility != View.VISIBLE) return
            recordingDotVisible = !recordingDotVisible
            binding.recordingDot.alpha = if (recordingDotVisible) 1f else 0f
            mainHandler.postDelayed(this, 650L)
        }
    }

    val isEmergencyRecording: Boolean get() = emergencyRecording

    fun updateNormalRecording(recording: Boolean) {
        normalRecording = recording
        onNormalRecordingChanged(recording)
        refreshRecordingUi()
    }

    fun setEmergencyRecordingActive(active: Boolean, endsAtMs: Long = 0L) {
        emergencyRecording = active
        binding.btnVideoPlayback.isChecked = active
        binding.btnStartRecord.isEnabled = !active || normalRecording
        binding.emergencyRecordingProgress.visibility = if (active) View.VISIBLE else View.GONE
        stopEmergencyProgressAnimation()
        binding.btnVideoPlayback.contentDescription = if (active) "停止紧急录制" else "紧急录制"
        mainHandler.removeCallbacks(emergencyRecordingTicker)
        if (active) {
            emergencyRecordingEndsAtMs = endsAtMs.takeIf { it > SystemClock.elapsedRealtime() }
                ?: (SystemClock.elapsedRealtime() + emergencyRecordingDurationMs)
            startEmergencyProgressAnimation()
            emergencyRecordingTicker.run()
        } else {
            emergencyRecordingEndsAtMs = 0L
            binding.emergencyRecordingProgress.progress = 0f
        }
        refreshRecordingUi()
    }

    fun emergencyRepeatToastMessage(): String? {
        if (!emergencyRecording) return null
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmergencyRepeatToastMs <= EMERGENCY_REPEAT_TOAST_INTERVAL_MS) return null
        lastEmergencyRepeatToastMs = now
        return "紧急录制中（${remainingEmergencySeconds(now)}s）"
    }

    fun destroy() {
        mainHandler.removeCallbacks(emergencyRecordingTicker)
        mainHandler.removeCallbacks(recordingDotBlinker)
        stopNormalRecordingAnimation()
        stopEmergencyProgressAnimation()
        stopRecordingDotAnimation()
    }

    private fun refreshRecordingUi() {
        binding.tvRecordingPill.visibility = if (normalRecording || emergencyRecording) View.VISIBLE else View.GONE
        binding.btnStartRecord.isChecked = normalRecording
        binding.normalRecordingProgress.visibility = if (normalRecording) View.VISIBLE else View.GONE
        if (normalRecording) startNormalRecordingAnimation() else stopNormalRecordingAnimation()
        binding.btnStartRecord.contentDescription = if (normalRecording) "停止录制" else "开始录制"
        if (normalRecording || emergencyRecording) startRecordingDotAnimation() else stopRecordingDotAnimation()
        updateRecordingPillText()
    }

    private fun updateRecordingPillText() {
        binding.tvRecordingStateText.text = if (emergencyRecording) {
            "录制中 (${remainingEmergencySeconds(SystemClock.elapsedRealtime())}s)"
        } else {
            "录制中"
        }
    }

    private fun remainingEmergencySeconds(now: Long): Long {
        val remainingMs = (emergencyRecordingEndsAtMs - now).coerceIn(0L, emergencyRecordingDurationMs)
        return ((remainingMs + 999L) / 1000L).coerceIn(0L, emergencyRecordingDurationMs / 1000L)
    }

    private fun startNormalRecordingAnimation() {
        if (normalRecordingAnimator?.isStarted == true) return
        normalRecordingAnimator = ObjectAnimator.ofFloat(binding.normalRecordingProgress, View.ROTATION, 0f, 360f).apply {
            duration = 3000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopNormalRecordingAnimation() {
        normalRecordingAnimator?.cancel()
        normalRecordingAnimator = null
        binding.normalRecordingProgress.rotation = 0f
    }

    private fun startEmergencyProgressAnimation() {
        val remainingMs = (emergencyRecordingEndsAtMs - SystemClock.elapsedRealtime())
            .coerceIn(0L, emergencyRecordingDurationMs)
        val progress = 1f - (remainingMs.toFloat() / emergencyRecordingDurationMs.toFloat())
        binding.emergencyRecordingProgress.progress = progress
        emergencyProgressAnimator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = remainingMs
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                binding.emergencyRecordingProgress.progress = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopEmergencyProgressAnimation() {
        emergencyProgressAnimator?.cancel()
        emergencyProgressAnimator = null
    }

    private fun startRecordingDotAnimation() {
        if (recordingDotBlinking) return
        recordingDotBlinking = true
        mainHandler.removeCallbacks(recordingDotBlinker)
        recordingDotVisible = true
        binding.recordingDot.alpha = 1f
        mainHandler.postDelayed(recordingDotBlinker, 650L)
    }

    private fun stopRecordingDotAnimation() {
        recordingDotBlinking = false
        mainHandler.removeCallbacks(recordingDotBlinker)
        recordingDotVisible = true
        binding.recordingDot.alpha = 1f
    }

    private companion object {
        private const val EMERGENCY_REPEAT_TOAST_INTERVAL_MS = 1_500L
    }
}
