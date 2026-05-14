package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import com.kooo.evcam.v2.log.V2AppLog

class V2BlindSpotSecondaryDisplayOverlay(private val appContext: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    @Volatile private var showing = false
    private var onSurfaceReady: ((Surface) -> Unit)? = null
    private var onSurfaceDestroyed: (() -> Unit)? = null

    fun show(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rotation: Int,
        showBorder: Boolean,
        onSurfaceReady: (Surface) -> Unit,
        onSurfaceDestroyed: () -> Unit,
    ) {
        this.onSurfaceReady = onSurfaceReady
        this.onSurfaceDestroyed = onSurfaceDestroyed
        mainHandler.post { showOnMainThread(displayId, x, y, width, height, rotation, showBorder) }
    }

    fun hide() {
        mainHandler.post { hideOnMainThread() }
    }

    fun isShowing(): Boolean = showing

    private fun showOnMainThread(displayId: Int, x: Int, y: Int, width: Int, height: Int, rotation: Int, showBorder: Boolean) {
        hideOnMainThread()

        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            V2AppLog.w(TAG, "secondary display not found id=$displayId")
            return
        }

        val displayContext = appContext.createDisplayContext(display)
        val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(displayContext)
        val sv = SurfaceView(displayContext).apply {
            this.rotation = rotation.toFloat()
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                if (surface.isValid) {
                    V2AppLog.i(TAG, "secondary display surface created")
                    onSurfaceReady?.invoke(surface)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                V2AppLog.d(TAG, "secondary display surface changed ${w}x$h format=$format")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                V2AppLog.i(TAG, "secondary display surface destroyed")
                onSurfaceDestroyed?.invoke()
            }
        })
        if (showBorder) {
            val border = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(4, Color.WHITE)
            }
            container.background = border
        }
        container.addView(sv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ))

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        runCatching { wm.addView(container, params) }.onFailure {
            V2AppLog.w(TAG, "failed to add secondary display overlay", it)
            return
        }

        windowManager = wm
        rootView = container
        surfaceView = sv
        showing = true
        V2AppLog.i(TAG, "secondary display overlay shown id=$displayId ${width}x$height at $x,$y rotation=$rotation border=$showBorder")
    }

    private fun hideOnMainThread() {
        val wm = windowManager ?: return
        val view = rootView ?: return
        onSurfaceDestroyed?.invoke()
        runCatching { wm.removeViewImmediate(view) }.onFailure {
            V2AppLog.w(TAG, "failed to remove secondary display overlay", it)
        }
        surfaceView = null
        rootView = null
        windowManager = null
        onSurfaceReady = null
        onSurfaceDestroyed = null
        showing = false
        V2AppLog.i(TAG, "secondary display overlay hidden")
    }

    private companion object {
        private const val TAG = "V2BlindSpotSecondary"
    }
}
