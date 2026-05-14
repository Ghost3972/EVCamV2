package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands

class V2BlindSpotTestOverlay(private val appContext: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    @Volatile private var showing = false
    private var activeSide: String? = null

    fun show() {
        mainHandler.post { showOnMainThread() }
    }

    fun hide() {
        mainHandler.post { hideOnMainThread() }
    }

    fun isShowing(): Boolean = showing

    private fun showOnMainThread() {
        if (showing) return

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(200, 30, 30, 30))
            }
        }

        val leftBtn = createButton("左") { toggleSignal("left") }
        val rightBtn = createButton("右") { toggleSignal("right") }

        container.addView(leftBtn, LinearLayout.LayoutParams(dp(80), dp(60)).apply {
            marginEnd = dp(12)
        })
        container.addView(rightBtn, LinearLayout.LayoutParams(dp(80), dp(60)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }

        runCatching { wm.addView(container, params) }.onFailure {
            V2AppLog.w(TAG, "failed to show test overlay", it)
            return
        }

        windowManager = wm
        rootView = container
        showing = true
        V2AppLog.i(TAG, "test overlay shown")
    }

    private fun createButton(text: String, onClick: () -> Unit): TextView {
        val normal = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(230, 60, 60, 60))
            setStroke(2, Color.WHITE)
        }
        return TextView(appContext).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = normal
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.6f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        if (event.action == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                    }
                }
                true
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleSignal(side: String) {
        if (activeSide == side) {
            V2CameraServiceCommands.hideBlindSpotPreview(appContext)
            activeSide = null
            V2AppLog.i(TAG, "test signal off side=$side")
        } else {
            activeSide = side
            V2CameraServiceCommands.showBlindSpotPreview(appContext, side)
            V2AppLog.i(TAG, "test signal on side=$side")
        }
    }

    private fun hideOnMainThread() {
        val wm = windowManager ?: return
        val view = rootView ?: return
        if (activeSide != null) {
            V2CameraServiceCommands.hideBlindSpotPreview(appContext)
            activeSide = null
        }
        runCatching { wm.removeViewImmediate(view) }.onFailure {
            V2AppLog.w(TAG, "failed to remove test overlay", it)
        }
        rootView = null
        windowManager = null
        showing = false
        V2AppLog.i(TAG, "test overlay hidden")
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val TAG = "V2BlindSpotTest"
    }
}
