package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.kooo.evcam.R

internal data class V2BlindSpotOverlayViews(
    val root: FrameLayout,
    val textureView: TextureView,
    val dragHandleView: View,
    val closeButtonView: View,
    val resizeButtonView: View,
    val resizeCornerView: View,
    val metricsView: TextView,
)

internal class V2BlindSpotOverlayViewFactory(
    private val context: Context,
    private val overlayStyle: V2BlindSpotOverlayStyle,
) {
    fun create(
        rootTouchListener: View.OnTouchListener,
        dragHandleTouchListener: View.OnTouchListener,
        resizeTouchListener: () -> View.OnTouchListener,
        closeAction: () -> Unit,
        surfaceTextureListener: TextureView.SurfaceTextureListener,
    ): V2BlindSpotOverlayViews {
        val texture = TextureView(context).apply {
            isOpaque = true
            this.surfaceTextureListener = surfaceTextureListener
        }
        val dragHandle = dragHandle(dragHandleTouchListener)
        val closeButton = overlayStyle.controlButton(
            iconRes = R.drawable.ic_close,
            size = overlayStyle.closeButtonSize(),
            iconSize = overlayStyle.dp(21f),
            backgroundAlpha = 0.42f,
            onClick = closeAction,
        )
        val resizeButton = overlayStyle.controlButton(
            iconRes = R.drawable.ic_blind_spot_resize,
            size = overlayStyle.controlButtonSize(),
            iconSize = overlayStyle.dp(30f),
            backgroundAlpha = 0.42f,
            onTouchListener = resizeTouchListener(),
        )
        val metrics = metricsView()
        val resizeCorner = resizeCorner(resizeTouchListener())
        val root = FrameLayout(context).apply {
            background = overlayStyle.roundedBackground()
            elevation = overlayStyle.dp(18f).toFloat()
            clipToOutline = true
            setOnTouchListener(rootTouchListener)
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(dragHandle, FrameLayout.LayoutParams(overlayStyle.dp(56f), overlayStyle.dp(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = overlayStyle.dp(10f)
            })
            addView(closeButton, FrameLayout.LayoutParams(overlayStyle.closeButtonSize(), overlayStyle.closeButtonSize(), Gravity.TOP or Gravity.START).apply {
                leftMargin = overlayStyle.edgeControlMargin()
                topMargin = overlayStyle.topControlMargin()
            })
            addView(resizeButton, FrameLayout.LayoutParams(overlayStyle.controlButtonSize(), overlayStyle.controlButtonSize(), Gravity.TOP or Gravity.END).apply {
                rightMargin = overlayStyle.edgeControlMargin()
                topMargin = overlayStyle.topControlMargin()
            })
            addView(metrics, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, overlayStyle.dp(74f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = overlayStyle.edgeControlMargin()
                bottomMargin = overlayStyle.bottomControlMargin()
            })
            addView(resizeCorner, FrameLayout.LayoutParams(overlayStyle.dp(30f), overlayStyle.dp(30f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = overlayStyle.dp(6f)
                bottomMargin = overlayStyle.dp(6f)
            })
        }
        return V2BlindSpotOverlayViews(
            root = root,
            textureView = texture,
            dragHandleView = dragHandle,
            closeButtonView = closeButton,
            resizeButtonView = resizeButton,
            resizeCornerView = resizeCorner,
            metricsView = metrics,
        )
    }

    private fun dragHandle(touchListener: View.OnTouchListener): View = View(context).apply {
        background = overlayStyle.dragBarBackground()
        setOnTouchListener(touchListener)
        contentDescription = "drag"
    }

    private fun metricsView(): TextView = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        includeFontPadding = true
        setLineSpacing(overlayStyle.dp(5f).toFloat(), 1f)
        setPadding(overlayStyle.dp(12f), overlayStyle.dp(8f), overlayStyle.dp(12f), overlayStyle.dp(10f))
        background = overlayStyle.pillBackground(0.34f)
    }

    private fun resizeCorner(touchListener: View.OnTouchListener): View = View(context).apply {
        alpha = 0.04f
        background = overlayStyle.controlBackground(0.18f)
        setOnTouchListener(touchListener)
        contentDescription = "resize corner"
    }
}
