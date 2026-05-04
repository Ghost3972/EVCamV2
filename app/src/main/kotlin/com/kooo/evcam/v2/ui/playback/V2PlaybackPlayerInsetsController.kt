package com.kooo.evcam.v2.ui.playback

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding

internal class V2PlaybackPlayerInsetsController(
    private val activity: Activity,
    private val binding: ActivityV2VideoPlaybackBinding,
    private val isShowingPlayer: () -> Boolean,
    private val isPlayerUiVisible: () -> Boolean,
) {
    var topInset = 0
        private set
    var bottomInset = 0
        private set
    var immersive = false
        private set

    fun install() {
        binding.previewContainer.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            if (top > 0 || bottom > 0 || isPlayerUiVisible()) {
                applyOverlayInsets(top, bottom)
            }
            applyVideoViewportCrop()
            insets
        }
    }

    fun exitImmersive() {
        immersive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.show(WindowInsets.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        applyVideoViewportCrop()
    }

    fun setImmersive(enabled: Boolean) {
        immersive = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, !enabled)
            activity.window.insetsController?.let { controller ->
                if (enabled) {
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        applyVideoViewportCrop()
        binding.previewContainer.post { binding.previewContainer.requestApplyInsets() }
    }

    fun applyVideoViewportCrop() {
        val cropForVisibleBars = isShowingPlayer() && isPlayerUiVisible() && !immersive
        val top = if (cropForVisibleBars) topInset.takeIf { it > 0 } ?: systemBarFallbackInset("status_bar_height") else 0
        val bottom = if (cropForVisibleBars) bottomInset.takeIf { it > 0 } ?: systemBarFallbackInset("navigation_bar_height") else 0
        updateFrameCrop(binding.videoFront, top, bottom)
        updateFrameCrop(binding.photoPreview, top, bottom)
        updateFrameCrop(binding.placeholderFront, top, bottom)
    }

    private fun applyOverlayInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        updateConstraintMargins(binding.playerTitleBar, top = top)
        updateConstraintMargins(binding.controlsLayout, bottom = bottom)
        updateConstraintMargins(binding.btnSnapshot, top = top, bottom = bottom)
    }

    private fun updateFrameCrop(view: View, top: Int, bottom: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val nextTop = -top
        val nextBottom = -bottom
        if (params.topMargin == nextTop && params.bottomMargin == nextBottom) return
        params.topMargin = nextTop
        params.bottomMargin = nextBottom
        view.layoutParams = params
    }

    private fun systemBarFallbackInset(name: String): Int {
        val id = activity.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    private fun updateConstraintMargins(view: View, top: Int? = null, bottom: Int? = null) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        var changed = false
        if (top != null && params.topMargin != top) {
            params.topMargin = top
            changed = true
        }
        if (bottom != null && params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            changed = true
        }
        if (changed) view.layoutParams = params
    }
}
