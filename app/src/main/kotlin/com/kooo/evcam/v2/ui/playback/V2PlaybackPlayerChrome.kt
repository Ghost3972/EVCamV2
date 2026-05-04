package com.kooo.evcam.v2.ui.playback

import android.app.Activity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding

internal class V2PlaybackPlayerChrome(
    private val activity: Activity,
    private val binding: ActivityV2VideoPlaybackBinding,
) {
    private val playerUiInterpolator = AccelerateDecelerateInterpolator()
    private var isShowingPlayer = false
    private var playerUiVisible = true
    private val playerInsets = V2PlaybackPlayerInsetsController(
        activity = activity,
        binding = binding,
        isShowingPlayer = { isShowingPlayer },
        isPlayerUiVisible = { playerUiVisible },
    )
    val showingPlayer: Boolean get() = isShowingPlayer

    fun installInsetsListener() {
        playerInsets.install()
    }

    fun showListMode(stopPlayback: Boolean, animate: Boolean = true, stopPlaybackUi: () -> Unit) {
        val wasShowingPlayer = isShowingPlayer
        isShowingPlayer = false
        binding.swipeRefresh.isEnabled = true
        binding.currentDatetime.visibility = View.GONE

        val listChrome = listOf(binding.toolbar, binding.btnRefresh, binding.btnSetting, binding.contentLayout)
        listChrome.forEach {
            it.animate().cancel()
            it.visibility = View.VISIBLE
        }
        binding.listContainer.visibility = View.VISIBLE

        if (animate && wasShowingPlayer && binding.previewContainer.visibility == View.VISIBLE) {
            setPlayerImmersive(false)
            listChrome.forEach {
                it.alpha = 0f
                it.animate()
                    .alpha(1f)
                    .setStartDelay(90L)
                    .setDuration(300L)
                    .setInterpolator(playerUiInterpolator)
                    .start()
            }
            binding.previewContainer.animate().cancel()
            binding.previewContainer.animate()
                .alpha(0f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(380L)
                .setInterpolator(playerUiInterpolator)
                .withEndAction {
                    binding.previewContainer.visibility = View.GONE
                    binding.previewContainer.alpha = 1f
                    binding.previewContainer.scaleX = 1f
                    binding.previewContainer.scaleY = 1f
                    setPlayerUiVisible(visible = false, animate = false, photoMode = false)
                    exitPlayerImmersive()
                    if (stopPlayback) stopPlaybackUi()
                }
                .start()
            return
        }

        exitPlayerImmersive()
        if (stopPlayback) stopPlaybackUi()
        listChrome.forEach { it.alpha = 1f }
        binding.previewContainer.visibility = View.GONE
        binding.previewContainer.alpha = 1f
        binding.previewContainer.scaleX = 1f
        binding.previewContainer.scaleY = 1f
        setPlayerUiVisible(visible = false, animate = false, photoMode = false)
    }

    fun showPlayerMode(photoMode: Boolean) {
        isShowingPlayer = true
        setPlayerImmersive(false)
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.isEnabled = false
        val listChrome = listOf(binding.toolbar, binding.btnRefresh, binding.btnSetting, binding.contentLayout)
        listChrome.forEach { it.animate().cancel() }
        binding.previewContainer.visibility = View.VISIBLE
        binding.previewContainer.bringToFront()
        binding.previewContainer.alpha = 0f
        binding.previewContainer.scaleX = 0.985f
        binding.previewContainer.scaleY = 0.985f
        setPlayerUiVisible(visible = true, animate = false, photoMode = photoMode)
        binding.previewContainer.animate().cancel()
        binding.previewContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(360L)
            .setInterpolator(playerUiInterpolator)
            .withEndAction {
                if (isShowingPlayer) {
                    listChrome.forEach {
                        it.visibility = View.GONE
                        it.alpha = 1f
                    }
                }
            }
            .start()
    }

    fun togglePlayerUi(photoMode: Boolean) {
        if (!isShowingPlayer) return
        setPlayerUiVisible(!playerUiVisible, animate = true, photoMode = photoMode)
    }

    fun exitPlayerImmersive() {
        playerInsets.exitImmersive()
    }

    fun updateActionsForMode(isPhoto: Boolean) {
        binding.btnSnapshot.visibility = if (isPhoto) View.GONE else binding.btnSnapshot.visibility
        binding.controlsLayout.visibility = if (isPhoto) View.GONE else binding.controlsLayout.visibility
        binding.btnPlayerMove.visibility = if (isPhoto) View.GONE else View.VISIBLE
        binding.btnPlayerDelete.visibility = if (isPhoto) View.GONE else View.VISIBLE
    }

    private fun setPlayerUiVisible(visible: Boolean, animate: Boolean, photoMode: Boolean) {
        playerUiVisible = visible
        if (isShowingPlayer) {
            setPlayerImmersive(!visible)
        }

        updateActionsForMode(isPhoto = photoMode)
        val targets = if (photoMode) {
            listOf(binding.playerTitleBar)
        } else {
            listOf(binding.playerTitleBar, binding.btnSnapshot, binding.controlsLayout)
        }
        targets.forEach { it.animate().cancel() }
        if (photoMode) {
            binding.btnSnapshot.animate().cancel()
            binding.controlsLayout.animate().cancel()
            binding.btnSnapshot.visibility = View.GONE
            binding.controlsLayout.visibility = View.GONE
        }

        if (!animate) {
            val visibility = if (visible) View.VISIBLE else View.GONE
            targets.forEach {
                it.alpha = if (visible) 1f else 0f
                it.translationY = 0f
                it.visibility = visibility
            }
            return
        }

        if (visible) {
            targets.forEach {
                it.visibility = View.VISIBLE
                it.alpha = 0f
            }
            binding.playerTitleBar.translationY = -(binding.playerTitleBar.height + playerInsets.topInset).toFloat()
            binding.btnSnapshot.translationY = 0f
            binding.controlsLayout.translationY = (binding.controlsLayout.height + playerInsets.bottomInset).toFloat()
            animatePlayerOverlay(binding.playerTitleBar, alpha = 1f, translationY = 0f)
            animatePlayerOverlay(binding.btnSnapshot, alpha = 1f, translationY = 0f)
            animatePlayerOverlay(binding.controlsLayout, alpha = 1f, translationY = 0f)
        } else {
            animatePlayerOverlay(binding.playerTitleBar, alpha = 0f, translationY = -(binding.playerTitleBar.height + playerInsets.topInset).toFloat(), hideAfter = true)
            animatePlayerOverlay(binding.btnSnapshot, alpha = 0f, translationY = 0f, hideAfter = true)
            animatePlayerOverlay(binding.controlsLayout, alpha = 0f, translationY = (binding.controlsLayout.height + playerInsets.bottomInset).toFloat(), hideAfter = true)
        }
    }

    private fun animatePlayerOverlay(
        view: View,
        alpha: Float,
        translationY: Float,
        hideAfter: Boolean = false,
    ) {
        view.animate()
            .alpha(alpha)
            .translationY(translationY)
            .setDuration(480L)
            .setInterpolator(playerUiInterpolator)
            .withEndAction {
                if (hideAfter && !playerUiVisible) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }
            }
            .start()
    }

    private fun setPlayerImmersive(immersive: Boolean) {
        playerInsets.setImmersive(immersive)
    }
}
