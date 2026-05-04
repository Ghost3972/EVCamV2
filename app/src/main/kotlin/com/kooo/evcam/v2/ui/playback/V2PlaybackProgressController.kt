package com.kooo.evcam.v2.ui.playback

import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding

internal class V2PlaybackProgressController(
    private val binding: ActivityV2VideoPlaybackBinding,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val updater = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                val pos = binding.videoFront.currentPosition.coerceAtLeast(0)
                binding.currentTime.text = V2PlaybackTimeFormatter.formatDuration(pos)
                binding.seekBar.progress = pos.coerceAtMost(binding.seekBar.max.coerceAtLeast(1))
            }
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun attach() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = V2PlaybackTimeFormatter.formatDuration(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: 0
                binding.videoFront.seekTo(target)
                binding.currentTime.text = V2PlaybackTimeFormatter.formatDuration(target)
                userSeeking = false
            }
        })
    }

    fun start() {
        handler.removeCallbacks(updater)
        handler.post(updater)
    }

    fun stop() {
        handler.removeCallbacks(updater)
    }

    fun reset() {
        binding.currentTime.text = "00:00"
        binding.totalTime.text = "00:00"
        binding.seekBar.progress = 0
        binding.seekBar.max = 100
    }

    private companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L
    }
}
