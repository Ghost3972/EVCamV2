package com.kooo.evcam.v2.ui.main

import android.animation.ObjectAnimator
import android.os.Handler
import android.view.View
import android.view.animation.LinearInterpolator
import com.kooo.evcam.databinding.ActivityV2MainA7Binding

internal class V2MainRecordingUiController(
    private val binding: ActivityV2MainA7Binding,
    private val mainHandler: Handler,
    private val onNormalRecordingChanged: (Boolean) -> Unit,
) {
    private var normalRecordingAnimator: ObjectAnimator? = null
    private var recordingDotBlinking = false
    private var recordingDotVisible = true
    private var normalRecording = false

    private val recordingDotBlinker = object : Runnable {
        override fun run() {
            if (binding.tvRecordingPill.visibility != View.VISIBLE) return
            recordingDotVisible = !recordingDotVisible
            binding.recordingDot.alpha = if (recordingDotVisible) 1f else 0f
            mainHandler.postDelayed(this, 650L)
        }
    }

    fun updateNormalRecording(recording: Boolean) {
        normalRecording = recording
        onNormalRecordingChanged(recording)
        refreshRecordingUi()
    }

    fun destroy() {
        mainHandler.removeCallbacks(recordingDotBlinker)
        stopNormalRecordingAnimation()
        stopRecordingDotAnimation()
    }

    private fun refreshRecordingUi() {
        binding.tvRecordingPill.visibility = if (normalRecording) View.VISIBLE else View.GONE
        binding.btnStartRecord.isChecked = normalRecording
        binding.normalRecordingProgress.visibility = if (normalRecording) View.VISIBLE else View.GONE
        if (normalRecording) startNormalRecordingAnimation() else stopNormalRecordingAnimation()
        binding.btnStartRecord.contentDescription = if (normalRecording) "停止录制" else "开始录制"
        if (normalRecording) startRecordingDotAnimation() else stopRecordingDotAnimation()
        updateRecordingPillText()
    }

    private fun updateRecordingPillText() {
        binding.tvRecordingStateText.text = "录制中"
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
}
