package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal class V2BlindSpotWindowLayoutController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onLayoutApplied: () -> Unit,
) {
    var currentParams: WindowManager.LayoutParams? = null
        private set

    private var layoutUpdatePending = false
    private var lastLayoutUpdateRequestMs = 0L
    private var lastLayoutX = Int.MIN_VALUE
    private var lastLayoutY = Int.MIN_VALUE
    private var lastLayoutWidth = Int.MIN_VALUE
    private var lastLayoutHeight = Int.MIN_VALUE

    fun createParams(side: String): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val defaultWidth = (metrics.widthPixels * 0.28f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.86f).toInt()
        val defaultX = (metrics.widthPixels * 0.03f).toInt()
        val defaultY = ((metrics.heightPixels - defaultHeight) / 2).coerceAtLeast(0)
        val config = V2SettingsRepository.blindSpotOverlayConfig(context, side, defaultX, defaultY, defaultWidth, defaultHeight)
        val width = clampWidth(config.width)
        val height = clampHeight(config.height)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = clampX(config.x, width)
            y = clampY(config.y, height)
            currentParams = this
            resetLayoutCache()
        }
    }

    fun addView(view: View): Boolean {
        val params = currentParams ?: return false
        return runCatching { windowManager.addView(view, params) }
            .onFailure { V2AppLog.e("V2BlindSpotOverlay", "add overlay failed", it) }
            .isSuccess
    }

    fun removeView(view: View) {
        runCatching { windowManager.removeView(view) }
            .onFailure { V2AppLog.e("V2BlindSpotOverlay", "remove overlay failed", it) }
    }

    fun clear() {
        currentParams = null
        layoutUpdatePending = false
        resetLayoutCache()
    }

    fun requestUpdate(view: View) {
        val params = currentParams ?: return
        if (isCachedLayout(params)) return
        if (layoutUpdatePending) return
        layoutUpdatePending = true
        val now = SystemClock.uptimeMillis()
        val delayMs = (LAYOUT_UPDATE_INTERVAL_MS - (now - lastLayoutUpdateRequestMs)).coerceAtLeast(0L)
        view.postDelayed({
            layoutUpdatePending = false
            lastLayoutUpdateRequestMs = SystemClock.uptimeMillis()
            updateNow(view)
        }, delayMs)
    }

    fun updateNow(view: View) {
        val params = currentParams ?: return
        layoutUpdatePending = false
        if (isCachedLayout(params)) return
        cacheLayout(params)
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { V2AppLog.w("V2BlindSpotOverlay", "update overlay layout failed", it) }
        onLayoutApplied()
    }

    fun saveBounds(side: String) {
        val params = currentParams ?: return
        V2BlindSpotSettings.setOverlayBounds(context, side, params.x, params.y, params.width, params.height)
    }

    fun clampX(x: Int, windowWidth: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return x.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))
    }

    fun clampY(y: Int, windowHeight: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
    }

    fun clampWidth(width: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return width.coerceIn((screenWidth * 0.18f).toInt().coerceAtLeast(240), (screenWidth * 0.90f).toInt().coerceAtLeast(240))
    }

    fun clampHeight(height: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return height.coerceIn((screenHeight * 0.25f).toInt().coerceAtLeast(240), (screenHeight * 0.95f).toInt().coerceAtLeast(240))
    }

    private fun isCachedLayout(params: WindowManager.LayoutParams): Boolean {
        return params.x == lastLayoutX &&
            params.y == lastLayoutY &&
            params.width == lastLayoutWidth &&
            params.height == lastLayoutHeight
    }

    private fun cacheLayout(params: WindowManager.LayoutParams) {
        lastLayoutX = params.x
        lastLayoutY = params.y
        lastLayoutWidth = params.width
        lastLayoutHeight = params.height
    }

    private fun resetLayoutCache() {
        lastLayoutX = Int.MIN_VALUE
        lastLayoutY = Int.MIN_VALUE
        lastLayoutWidth = Int.MIN_VALUE
        lastLayoutHeight = Int.MIN_VALUE
    }

    private companion object {
        private const val LAYOUT_UPDATE_INTERVAL_MS = 16L
    }
}
