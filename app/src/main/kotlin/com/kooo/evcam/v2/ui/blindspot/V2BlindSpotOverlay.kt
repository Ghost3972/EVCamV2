package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog

class V2BlindSpotOverlay(
    private val context: Context,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
    private val previewInputSize: (Int) -> Size?,
    private val renderedFrames: (Int) -> Long,
    private val onClose: (() -> Unit)? = null,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayStyle = V2BlindSpotOverlayStyle(context)
    private val transformStore = V2BlindSpotOverlayTransformStore(context)
    private val overlayViewFactory = V2BlindSpotOverlayViewFactory(context, overlayStyle)
    private val previewSurfaceController = V2BlindSpotPreviewSurfaceController(
        attachPreview = attachPreview,
        detachPreview = detachPreview,
        previewInputSize = previewInputSize,
    )
    private val windowLayout = V2BlindSpotWindowLayoutController(context, windowManager) {
        applyPreviewTransform(textureView)
        updateMetricsText()
    }
    private val gestureController = V2BlindSpotOverlayGestureController(
        windowLayout = windowLayout,
        rootView = { root },
        resizeButtonView = { resizeButtonView },
        currentSide = { currentSide },
        shouldHandleRootTouch = ::shouldHandleRootTouch,
        onPreviewTransformNeeded = { applyPreviewTransform(textureView) },
    )
    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var transform = V2BlindSpotOverlayTransform()
    private var cameraIndex: Int = -1
    private var dragHandleView: View? = null
    private var closeButtonView: View? = null
    private var resizeButtonView: View? = null
    private var rotateButtonView: View? = null
    private var resizeCornerView: View? = null
    private var metricsView: TextView? = null
    private var currentSide: String = "left"
    private val overlayMetrics = V2BlindSpotOverlayMetrics(renderedFrames)

    private val metricsRunnable = object : Runnable {
        override fun run() {
            updateMetricsText()
            root?.postDelayed(this, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        }
    }

    fun show(side: String, index: Int) {
        val startedMs = SystemClock.elapsedRealtime()
        if (root != null) {
            val previousSide = currentSide
            currentSide = side
            loadTransformForSide(side)
            applyPreviewTransform(textureView)
            if (cameraIndex == index && previewSurfaceController.isAttached(index)) {
                return
            }
            cameraIndex = index
            previewSurfaceController.refresh(index, textureView)
            resetMetricsCounter()
            animateSideSwitch(previousSide, side)
            V2AppLog.perf("V2BlindSpotPerf", "switchOverlay", SystemClock.elapsedRealtime() - startedMs, "side=$side index=$index previousSide=$previousSide")
            return
        }
        hide()
        currentSide = side
        cameraIndex = index
        loadTransformForSide(side)
        val overlayViews = overlayViewFactory.create(
            rootTouchListener = gestureController.rootDragTouchListener,
            dragHandleTouchListener = gestureController.dragHandleTouchListener,
            resizeTouchListener = { gestureController.createResizeTouchListener() },
            closeAction = { hideFromCloseButton() },
            rotateAction = { rotatePreview() },
            surfaceTextureListener = surfaceTextureListener(index),
        )
        textureView = overlayViews.textureView
        root = overlayViews.root
        dragHandleView = overlayViews.dragHandleView
        closeButtonView = overlayViews.closeButtonView
        resizeButtonView = overlayViews.resizeButtonView
        rotateButtonView = overlayViews.rotateButtonView
        resizeCornerView = overlayViews.resizeCornerView
        metricsView = overlayViews.metricsView
        applyPreviewTransform(textureView)
        windowLayout.createParams(currentSide)
        if (!windowLayout.addView(root ?: return)) {
            root = null
            textureView = null
            windowLayout.clear()
            cameraIndex = -1
            return
        }
        val texture = textureView
        if (texture?.isAvailable == true && texture.surfaceTexture != null) previewSurfaceController.attach(index, texture.surfaceTexture!!)
        resetMetricsCounter()
        root?.postDelayed(metricsRunnable, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        V2AppLog.perf("V2BlindSpotPerf", "showOverlay", SystemClock.elapsedRealtime() - startedMs, "side=$side index=$index")
    }

    fun hide() {
        val oldRoot = root ?: return
        oldRoot.removeCallbacks(metricsRunnable)
        previewSurfaceController.detach()
        windowLayout.removeView(oldRoot)
        root = null
        textureView = null
        dragHandleView = null
        closeButtonView = null
        resizeButtonView = null
        rotateButtonView = null
        resizeCornerView = null
        metricsView = null
        windowLayout.clear()
        cameraIndex = -1
        resetMetricsCounter()
        gestureController.reset()
        V2AppLog.i("V2BlindSpotOverlay", "hide")
    }

    private fun surfaceTextureListener(index: Int): TextureView.SurfaceTextureListener {
        return object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(textureView)
                previewSurfaceController.attach(index, surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(textureView)
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                overlayMetrics.onFrameDisplayed()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurfaceController.detach()
                return true
            }
        }
    }

    private fun animateSideSwitch(fromSide: String, toSide: String) {
        if (fromSide == toSide) return
        val texture = textureView ?: return
        val direction = if (toSide == "right") 1f else -1f
        val distance = ((windowLayout.currentParams?.width ?: texture.width).coerceAtLeast(1) * 0.18f) * direction
        texture.animate().cancel()
        texture.alpha = 0.35f
        texture.translationX = distance
        texture.translationY = 0f
        texture.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(SIDE_SWITCH_ANIMATION_MS)
            .start()
    }

    private fun resetMetricsCounter() {
        overlayMetrics.reset(windowLayout.currentParams, metricsView)
    }

    private fun updateMetricsText(fps: Float? = null) {
        overlayMetrics.updateText(windowLayout.currentParams, cameraIndex, metricsView, fps)
    }

    private fun rotatePreview() {
        transform = transformStore.rotateClockwise(currentSide, transform.rotationDegrees)
        ensureWindowSizeMatchesRotationForUserRotate()
        applyPreviewTransform(textureView)
        V2AppLog.i("V2BlindSpotOverlay", "rotate preview side=$currentSide value=${transform.rotationDegrees}")
    }

    private fun loadTransformForSide(side: String) {
        transform = transformStore.load(side)
    }

    private fun ensureWindowSizeMatchesRotationForUserRotate(updateLayout: Boolean = true) {
        if (updateLayout) updateMetricsText()
    }

    private fun hideFromCloseButton() {
        onClose?.invoke() ?: hide()
    }

    private fun applyPreviewTransform(texture: TextureView?) {
        V2BlindSpotTransform.apply(
            texture = texture,
            overlayRotationDegrees = transform.rotationDegrees,
            correction = transform.correction,
        )
    }

    private fun shouldHandleRootTouch(x: Float, y: Float): Boolean {
        return V2BlindSpotOverlayHitTester.isOutsideControls(
            x,
            y,
            closeButtonView,
            resizeButtonView,
            rotateButtonView,
            dragHandleView,
            metricsView,
            resizeCornerView,
        )
    }

    companion object {
        private const val SIDE_SWITCH_ANIMATION_MS = 180L
    }
}
