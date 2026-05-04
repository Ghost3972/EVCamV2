package com.kooo.evcam.v2.ui.playback

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.Toast
import android.widget.VideoView
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

internal data class V2PlaybackCurrentVideo(
    val group: V2VideoGroup,
    val file: File,
)

internal class V2PlaybackPlayerController(
    private val activity: Activity,
    private val binding: ActivityV2VideoPlaybackBinding,
    private val onSnapshotRequested: () -> Unit,
    private val onMoveRequested: () -> Unit,
    private val onDeleteRequested: () -> Unit,
) {
    private val chrome = V2PlaybackPlayerChrome(activity, binding)
    private val progress = V2PlaybackProgressController(binding)
    private val playerTitleDateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    private val playerTitleTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private var selected: V2VideoGroup? = null
    private var pendingVideo: File? = null
    val showingPlayer: Boolean get() = chrome.showingPlayer

    fun attach() {
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnPlayerBack.setOnClickListener { showListMode(stopPlayback = true) }
        binding.btnSnapshot.setOnClickListener { onSnapshotRequested() }
        binding.btnPlayerMove.setOnClickListener { onMoveRequested() }
        binding.btnPlayerDelete.setOnClickListener { onDeleteRequested() }
        binding.gridContainer.setOnClickListener { togglePlayerUi() }
        binding.videoFront.setOnClickListener { togglePlayerUi() }
        binding.photoPreview.setOnClickListener { togglePlayerUi() }
        binding.placeholderFront.setOnClickListener { togglePlayerUi() }
        chrome.installInsetsListener()
        progress.attach()
        binding.videoFront.setOnErrorListener { _, _, _ ->
            showPlaybackError("视频无法播放或文件未完成: ${pendingVideo?.name ?: "未知文件"}")
            true
        }
    }

    fun destroy() {
        chrome.exitPlayerImmersive()
        progress.stop()
        binding.videoFront.stopPlayback()
    }

    fun pauseForLifecycle() {
        chrome.exitPlayerImmersive()
        pausePlayback()
    }

    fun clearSelection() {
        selected = null
        pendingVideo = null
    }

    fun currentVideoForDeletion(): V2PlaybackCurrentVideo? {
        val group = selected?.takeIf { !it.isPhoto } ?: return null
        val file = pendingVideo ?: group.composite ?: return null
        return if (file.exists()) V2PlaybackCurrentVideo(group, file) else null
    }

    fun stopCurrentPlaybackUi() {
        progress.stop()
        binding.videoFront.stopPlayback()
        binding.videoFront.visibility = View.VISIBLE
        binding.photoPreview.visibility = View.GONE
        binding.photoPreview.setImageDrawable(null)
        binding.placeholderFront.visibility = View.VISIBLE
        setPlaybackButtonState(playing = false)
        progress.reset()
    }

    fun showListMode(stopPlayback: Boolean, animate: Boolean = true) {
        chrome.showListMode(stopPlayback, animate) { stopCurrentPlaybackUi() }
    }

    fun play(group: V2VideoGroup) {
        selected = group
        group.composite?.takeIf { group.isPhoto }?.let {
            showPhoto(group, it)
            return
        }
        chrome.showPlayerMode(photoMode = false)
        updatePlayerTitle(group)
        val primary = group.composite
        binding.placeholderFront.visibility = if (primary == null) View.VISIBLE else View.GONE
        playFile(binding.videoFront, primary, primary = true)
    }

    fun exitPlayerImmersive() {
        chrome.exitPlayerImmersive()
    }

    fun pausePlayback() {
        if (binding.videoFront.isPlaying) binding.videoFront.pause()
        progress.stop()
        setPlaybackButtonState(playing = false)
    }

    private fun playFile(view: VideoView, file: File?, primary: Boolean) {
        if (file == null || !file.isFile || !file.canRead() || file.length() <= 0L) {
            pendingVideo = null
            view.stopPlayback()
            binding.placeholderFront.visibility = View.VISIBLE
            return
        }
        pendingVideo = file
        chrome.updateActionsForMode(isPhoto = false)
        binding.photoPreview.visibility = View.GONE
        binding.videoFront.visibility = View.VISIBLE
        binding.placeholderFront.visibility = View.GONE
        setPlaybackButtonState(playing = false)
        view.setVideoURI(Uri.fromFile(file))
        view.setOnPreparedListener { player ->
            player.isLooping = false
            if (primary) {
                binding.totalTime.text = V2PlaybackTimeFormatter.formatDuration(player.duration)
                binding.seekBar.max = player.duration.coerceAtLeast(1)
                binding.seekBar.progress = 0
                binding.currentTime.text = "00:00"
                progress.start()
            }
            player.start()
            if (primary) setPlaybackButtonState(playing = true)
        }
        view.setOnCompletionListener {
            if (primary) {
                setPlaybackButtonState(playing = false)
                progress.stop()
                binding.seekBar.progress = binding.seekBar.max
                binding.currentTime.text = binding.totalTime.text
            }
        }
    }

    private fun showPlaybackError(message: String) {
        binding.videoFront.stopPlayback()
        progress.stop()
        binding.videoFront.visibility = View.VISIBLE
        binding.photoPreview.visibility = View.GONE
        binding.placeholderFront.visibility = View.VISIBLE
        setPlaybackButtonState(playing = false)
        progress.reset()
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback() {
        if (selected?.isPhoto == true) return
        if (binding.videoFront.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun updatePlayerTitle(group: V2VideoGroup) {
        val parsed = V2VideoScanner.parseTimestamp(group.timestamp)
        if (parsed != null) {
            binding.playerTitleDate.text = playerTitleDateFormat.format(parsed)
            binding.playerTitleTime.text = playerTitleTimeFormat.format(parsed)
        } else {
            binding.playerTitleDate.text = group.displayDate
            binding.playerTitleTime.text = group.displayTime
        }
    }

    private fun togglePlayerUi() {
        chrome.togglePlayerUi(photoMode = selected?.isPhoto == true)
    }

    private fun setPlaybackButtonState(playing: Boolean) {
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.v2_player_ic_pause else R.drawable.v2_player_ic_play)
        binding.btnPlayPause.contentDescription = if (playing) "暂停" else "播放"
    }

    private fun startPlayback() {
        if (selected?.isPhoto == true) return
        binding.videoFront.start()
        progress.start()
        setPlaybackButtonState(playing = true)
    }

    private fun showPhoto(group: V2VideoGroup, file: File) {
        chrome.showPlayerMode(photoMode = true)
        updatePlayerTitle(group)
        pendingVideo = null
        progress.stop()
        chrome.updateActionsForMode(isPhoto = true)
        binding.videoFront.stopPlayback()
        binding.videoFront.visibility = View.GONE
        binding.photoPreview.visibility = View.VISIBLE
        binding.photoPreview.setImageURI(Uri.fromFile(file))
        binding.placeholderFront.visibility = View.GONE
        progress.reset()
        setPlaybackButtonState(playing = false)
    }
}
