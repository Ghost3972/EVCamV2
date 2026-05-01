package com.kooo.evcam.v2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2BlindSpotSettings

class V2BlindSpotOverlay(
    private val context: Context,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
    private val previewInputSize: (Int) -> Size?,
    private val renderedFrames: (Int) -> Long,
    private val onClose: (() -> Unit)? = null,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var rotationDegrees = 0
    private var cameraIndex: Int = -1
    private var attachedPreviewIndex: Int = -1
    private var layoutUpdatePending = false
    private var lastLayoutUpdateRequestMs = 0L
    private var lastLayoutX = Int.MIN_VALUE
    private var lastLayoutY = Int.MIN_VALUE
    private var lastLayoutWidth = Int.MIN_VALUE
    private var lastLayoutHeight = Int.MIN_VALUE
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var dragInProgress = false
    private var resizeInProgress = false
    private var dragHandleView: View? = null
    private var closeButtonView: View? = null
    private var resizeButtonView: View? = null
    private var rotateButtonView: View? = null
    private var resizeCornerView: View? = null
    private var metricsView: TextView? = null
    private var currentSide: String = "left"
    private var correction = V2BlindSpotSettings.Correction()
    private var windowSwapped = false
    private var lastFpsFrames = 0L
    private var fpsWindowStartedMs = 0L

    private val metricsRunnable = object : Runnable {
        override fun run() {
            updateMetricsText()
            root?.postDelayed(this, METRICS_UPDATE_INTERVAL_MS)
        }
    }

    private val rootDragListener = OverlayDragTouchListener(::shouldHandleRootTouch)
    private val dragHandleListener = OverlayDragTouchListener()

    fun show(side: String, index: Int) {
        if (root != null) {
            val previousSide = currentSide
            currentSide = side
            loadTransformForSide(side)
            updateWindowSwappedState()
            applyPreviewTransform(textureView)
            if (cameraIndex == index && previewSurface?.isValid == true) {
                return
            }
            cameraIndex = index
            refreshCurrentSurface(index)
            animateSideSwitch(previousSide, side)
            V2AppLog.i("V2BlindSpotOverlay", "switch side=$side index=$index")
            return
        }
        hide()
        currentSide = side
        cameraIndex = index
        val texture = TextureView(context).apply {
            isOpaque = true
        }
        loadTransformForSide(side)
        applyPreviewTransform(texture)
        textureView = texture
        root = FrameLayout(context).apply {
            background = roundedBackground()
            elevation = dp(18f).toFloat()
            clipToOutline = true
            setOnTouchListener(rootDragListener)
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dragHandleView = View(context).apply {
                background = dragBarBackground()
                setOnTouchListener(dragHandleListener)
                contentDescription = "drag"
            }
            addView(dragHandleView, FrameLayout.LayoutParams(dp(56f), dp(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10f)
            })
            closeButtonView = controlButton(
                iconRes = R.drawable.ic_close,
                size = closeButtonSize(),
                iconSize = dp(21f),
                backgroundAlpha = 0.42f,
                onClick = { hideFromCloseButton() }
            )
            addView(closeButtonView, FrameLayout.LayoutParams(closeButtonSize(), closeButtonSize(), Gravity.TOP or Gravity.START).apply {
                leftMargin = edgeControlMargin()
                topMargin = topControlMargin()
            })
            resizeButtonView = controlButton(
                iconRes = R.drawable.ic_blind_spot_resize,
                size = controlButtonSize(),
                iconSize = dp(30f),
                backgroundAlpha = 0.42f,
                onTouchListener = ResizeTouchListener()
            )
            addView(resizeButtonView, FrameLayout.LayoutParams(controlButtonSize(), controlButtonSize(), Gravity.TOP or Gravity.END).apply {
                rightMargin = edgeControlMargin()
                topMargin = topControlMargin()
            })
            rotateButtonView = controlButton(
                iconRes = R.drawable.ic_blind_spot_rotate,
                size = controlButtonSize(),
                iconSize = dp(23f),
                backgroundAlpha = 0.34f,
                onClick = { rotatePreview() }
            )
            addView(rotateButtonView, FrameLayout.LayoutParams(controlButtonSize(), controlButtonSize(), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = edgeControlMargin()
                bottomMargin = bottomControlMargin()
            })
            metricsView = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                includeFontPadding = true
                setLineSpacing(dp(5f).toFloat(), 1f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
                background = pillBackground(0.34f)
            }
            addView(metricsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(74f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = edgeControlMargin()
                bottomMargin = bottomControlMargin()
            })
            resizeCornerView = View(context).apply {
                alpha = 0.04f
                background = controlBackground(0.18f)
                setOnTouchListener(ResizeTouchListener())
                contentDescription = "resize corner"
            }
            addView(resizeCornerView, FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(6f)
                bottomMargin = dp(6f)
            })
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(texture)
                attachSurface(index, surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(texture)
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                detachCurrentSurface()
                return true
            }
        }
        windowParams = layoutParams()
        updateWindowSwappedState()
        runCatching { windowManager.addView(root, windowParams) }
            .onFailure {
                V2AppLog.e("V2BlindSpotOverlay", "add overlay failed", it)
                root = null
                textureView = null
                windowParams = null
                cameraIndex = -1
                return
            }
        if (texture.isAvailable && texture.surfaceTexture != null) attachSurface(index, texture.surfaceTexture!!)
        resetMetricsCounter()
        root?.post(metricsRunnable)
        V2AppLog.i("V2BlindSpotOverlay", "show side=$side index=$index")
    }

    fun hide() {
        val oldRoot = root ?: return
        oldRoot.removeCallbacks(metricsRunnable)
        detachCurrentSurface()
        runCatching { windowManager.removeView(oldRoot) }
            .onFailure { V2AppLog.e("V2BlindSpotOverlay", "remove overlay failed", it) }
        root = null
        textureView = null
        dragHandleView = null
        closeButtonView = null
        resizeButtonView = null
        rotateButtonView = null
        resizeCornerView = null
        metricsView = null
        windowParams = null
        cameraIndex = -1
        windowSwapped = false
        resetMetricsCounter()
        dragInProgress = false
        resizeInProgress = false
        V2AppLog.i("V2BlindSpotOverlay", "hide")
    }

    private fun attachSurface(index: Int, surfaceTexture: SurfaceTexture) {
        configurePreviewBufferSize(index, surfaceTexture)
        if (attachedPreviewIndex == index && previewSurfaceTexture == surfaceTexture && previewSurface?.isValid == true) return
        detachCurrentSurface()
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        previewSurfaceTexture = surfaceTexture
        attachedPreviewIndex = index
        attachPreview(index, surface)
        V2AppLog.i("V2BlindSpotOverlay", "attach preview index=$index valid=${surface.isValid}")
    }

    private fun animateSideSwitch(fromSide: String, toSide: String) {
        if (fromSide == toSide) return
        val texture = textureView ?: return
        val direction = if (toSide == "right") 1f else -1f
        val distance = ((windowParams?.width ?: texture.width).coerceAtLeast(1) * 0.18f) * direction
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
        lastFpsFrames = if (cameraIndex >= 0) renderedFrames(cameraIndex) else 0L
        fpsWindowStartedMs = android.os.SystemClock.elapsedRealtime()
        updateMetricsText(fps = 0f)
    }

    private fun updateMetricsText(fps: Float? = null) {
        val params = windowParams ?: return
        val index = cameraIndex
        val value = fps ?: run {
            if (index < 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = now - fpsWindowStartedMs
            if (elapsed <= 0L) return
            val frames = renderedFrames(index)
            val currentFps = (frames - lastFpsFrames).coerceAtLeast(0L) * 1000f / elapsed
            lastFpsFrames = frames
            fpsWindowStartedMs = now
            currentFps
        }
        metricsView?.text = String.format(java.util.Locale.US, "%dx%d\n%.1f fps", params.width, params.height, value)
    }

    private fun refreshCurrentSurface(index: Int) {
        val surfaceTexture = textureView?.surfaceTexture ?: return
        if (textureView?.isAvailable != true) return
        attachSurface(index, surfaceTexture)
    }

    private fun detachCurrentSurface() {
        val index = attachedPreviewIndex
        if (index >= 0) detachPreview(index)
        previewSurface?.release()
        previewSurface = null
        previewSurfaceTexture = null
        attachedPreviewIndex = -1
    }

    private fun rotatePreview() {
        rotationDegrees = (rotationDegrees + 90) % 360
        V2BlindSpotSettings.setOverlayRotation(context, currentSide, rotationDegrees)
        ensureWindowSizeMatchesRotationForUserRotate()
        applyPreviewTransform(textureView)
        V2AppLog.i("V2BlindSpotOverlay", "rotate preview side=$currentSide value=$rotationDegrees")
    }

    private fun loadTransformForSide(side: String) {
        rotationDegrees = V2BlindSpotSettings.overlayRotation(context, side)
        correction = if (V2BlindSpotSettings.isCorrectionEnabled(context)) {
            V2BlindSpotSettings.correction(context, side)
        } else {
            V2BlindSpotSettings.Correction()
        }
    }

    private fun updateWindowSwappedState() {
        windowSwapped = V2BlindSpotTransform.isCloserToPortrait(previewRotationDegrees())
    }

    private fun ensureWindowSizeMatchesRotationForUserRotate(updateLayout: Boolean = true) {
        val params = windowParams ?: return
        val desiredSwapped = V2BlindSpotTransform.isCloserToPortrait(previewRotationDegrees())
        val currentlyWide = params.width > params.height
        val currentlySwapped = !currentlyWide
        val shouldSwapBounds = desiredSwapped != currentlySwapped
        windowSwapped = desiredSwapped
        if (!shouldSwapBounds) return
        val view = root
        val nextWidth = clampWidth(params.height)
        val nextHeight = clampHeight(params.width)
        params.width = nextWidth
        params.height = nextHeight
        params.x = clampX(params.x, nextWidth)
        params.y = clampY(params.y, nextHeight)
        if (updateLayout && view != null) updateWindowLayoutNow(view)
        saveCurrentOverlayBounds(params)
        updateMetricsText()
    }

    private fun configurePreviewBufferSize(index: Int, surfaceTexture: SurfaceTexture) {
        val size = previewInputSize(index) ?: return
        if (size.width <= 0 || size.height <= 0) return
        runCatching { surfaceTexture.setDefaultBufferSize(size.width, size.height) }
            .onFailure { V2AppLog.w("V2BlindSpotOverlay", "set preview buffer size failed index=$index size=${size.width}x${size.height}", it) }
    }

    private fun hideFromCloseButton() {
        onClose?.invoke() ?: hide()
    }

    private fun applyPreviewTransform(texture: TextureView?) {
        V2BlindSpotTransform.apply(
            texture = texture,
            overlayRotationDegrees = rotationDegrees,
            correction = correction,
            windowSwapped = windowSwapped,
            previewSize = if (cameraIndex >= 0) previewInputSize(cameraIndex) else null,
        )
    }

    private fun previewRotationDegrees(): Float = V2BlindSpotTransform.effectiveRotation(rotationDegrees, correction)

    private fun layoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val defaultWidth = (metrics.widthPixels * 0.28f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.86f).toInt()
        val width = clampWidth(V2BlindSpotSettings.overlayWidth(context, currentSide, defaultWidth))
        val height = clampHeight(V2BlindSpotSettings.overlayHeight(context, currentSide, defaultHeight))
        val defaultX = (metrics.widthPixels * 0.03f).toInt()
        val defaultY = ((metrics.heightPixels - height) / 2).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = clampX(V2BlindSpotSettings.overlayX(context, currentSide, defaultX), width)
            y = clampY(V2BlindSpotSettings.overlayY(context, currentSide, defaultY), height)
        }
    }

    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun shouldHandleRootTouch(x: Float, y: Float): Boolean {
        return !isInViewHit(closeButton(), x, y)
            && !isInViewHit(resizeButton(), x, y)
            && !isInViewHit(rotateButton(), x, y)
            && !isInViewHit(dragHandle(), x, y)
            && !isInViewHit(metrics(), x, y)
            && !isInViewHit(resizeCornerHandle(), x, y)
    }

    private inner class OverlayDragTouchListener(
        private val touchFilter: ((Float, Float) -> Boolean)? = null,
    ) : View.OnTouchListener {
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = windowParams ?: return false
            val view = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (touchFilter?.invoke(event.x, event.y) == false) return false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartX = params.x
                    dragStartY = params.y
                    dragInProgress = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragInProgress) return false
                    val nextX = clampX(dragStartX + (event.rawX - dragStartRawX).toInt(), params.width)
                    val nextY = clampY(dragStartY + (event.rawY - dragStartRawY).toInt(), params.height)
                    if (kotlin.math.abs(nextX - params.x) >= MOVE_UPDATE_THRESHOLD_PX || kotlin.math.abs(nextY - params.y) >= MOVE_UPDATE_THRESHOLD_PX) {
                        params.x = nextX
                        params.y = nextY
                        requestWindowLayoutUpdate(view)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragInProgress) return false
                    dragInProgress = false
                    updateWindowLayoutNow(view)
                    saveCurrentOverlayBounds(params)
                    return true
                }
            }
            return true
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var active = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = windowParams ?: return false
            val view = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    resizeStartWidth = params.width
                    resizeStartHeight = params.height
                    resizeStartX = params.x
                    resizeStartY = params.y
                    resizeInProgress = true
                    active = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return false
                    val fromTopRightHandle = v === resizeButtonView
                    val deltaX = (event.rawX - resizeStartRawX).toInt()
                    val deltaY = (event.rawY - resizeStartRawY).toInt()
                    val nextWidth = clampWidth(resizeStartWidth + deltaX)
                    val nextHeight = if (fromTopRightHandle) {
                        clampHeight(resizeStartHeight - deltaY)
                    } else {
                        clampHeight(resizeStartHeight + deltaY)
                    }
                    if (kotlin.math.abs(nextWidth - params.width) >= RESIZE_UPDATE_THRESHOLD_PX || kotlin.math.abs(nextHeight - params.height) >= RESIZE_UPDATE_THRESHOLD_PX) {
                        params.width = nextWidth
                        params.height = nextHeight
                        params.x = clampX(resizeStartX, params.width)
                        params.y = if (fromTopRightHandle) {
                            clampY(resizeStartY + resizeStartHeight - nextHeight, params.height)
                        } else {
                            clampY(resizeStartY, params.height)
                        }
                        applyPreviewTransform(textureView)
                        requestWindowLayoutUpdate(view)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!active) return false
                    active = false
                    resizeInProgress = false
                    updateWindowLayoutNow(view)
                    applyPreviewTransform(textureView)
                    saveCurrentOverlayBounds(params)
                    return true
                }
            }
            return true
        }
    }

    private fun closeButton(): View? = closeButtonView

    private fun resizeButton(): View? = resizeButtonView

    private fun rotateButton(): View? = rotateButtonView

    private fun dragHandle(): View? = dragHandleView 

    private fun resizeCornerHandle(): View? = resizeCornerView

    private fun metrics(): View? = metricsView

    private fun isInViewHit(view: View?, x: Float, y: Float): Boolean {
        view ?: return false
        val left = view.left
        val top = view.top
        return x >= left && x <= view.right && y >= top && y <= view.bottom
    }

    private fun controlButton(
        iconRes: Int,
        size: Int,
        iconSize: Int,
        backgroundAlpha: Float,
        onClick: (() -> Unit)? = null,
        onTouchListener: View.OnTouchListener? = null,
    ): FrameLayout {
        return FrameLayout(context).apply {
            background = controlBackground(backgroundAlpha)
            isClickable = true
            isFocusable = false
            if (onClick != null) setOnClickListener { onClick() }
            if (onTouchListener != null) setOnTouchListener(onTouchListener)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER
            }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }.also {
            it.layoutParams = FrameLayout.LayoutParams(size, size)
        }
    }

    private fun saveCurrentOverlayBounds(params: WindowManager.LayoutParams) {
        V2BlindSpotSettings.setOverlayBounds(context, currentSide, params.x, params.y, params.width, params.height)
    }

    private fun roundedBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(34f).toFloat()
        setColor(0xF014171C.toInt())
        setStroke(dp(1f), 0x22FFFFFF)
    }

    private fun controlBackground(alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val a = (0xFF * alpha.coerceIn(0f, 1f)).toInt()
        setColor((a shl 24) or 0x101216)
        setStroke(dp(1f), 0x1CFFFFFF)
    }

    private fun pillBackground(alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15f).toFloat()
        val a = (0xFF * alpha.coerceIn(0f, 1f)).toInt()
        setColor((a shl 24) or 0x101216)
        setStroke(dp(1f), 0x1CFFFFFF)
    }

    private fun dragBarBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(2f).toFloat()
        setColor(0xCCFFFFFF.toInt())
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    private fun controlButtonSize(): Int = dp(52f)

    private fun closeButtonSize(): Int = dp(46f)

    private fun edgeControlMargin(): Int = dp(44f)

    private fun topControlMargin(): Int = dp(22f)

    private fun bottomControlMargin(): Int = dp(22f)

    private fun clampX(x: Int, windowWidth: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return x.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))
    }

    private fun clampY(y: Int, windowHeight: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
    }

    private fun clampWidth(width: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return width.coerceIn((screenWidth * 0.18f).toInt().coerceAtLeast(240), (screenWidth * 0.90f).toInt().coerceAtLeast(240))
    }

    private fun clampHeight(height: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return height.coerceIn((screenHeight * 0.25f).toInt().coerceAtLeast(240), (screenHeight * 0.95f).toInt().coerceAtLeast(240))
    }

    private fun requestWindowLayoutUpdate(view: View) {
        val params = windowParams ?: return
        if (params.x == lastLayoutX && params.y == lastLayoutY && params.width == lastLayoutWidth && params.height == lastLayoutHeight) return
        if (layoutUpdatePending) return
        layoutUpdatePending = true
        val now = SystemClock.uptimeMillis()
        val delayMs = (LAYOUT_UPDATE_INTERVAL_MS - (now - lastLayoutUpdateRequestMs)).coerceAtLeast(0L)
        view.postDelayed({
            layoutUpdatePending = false
            lastLayoutUpdateRequestMs = SystemClock.uptimeMillis()
            updateWindowLayoutNow(view)
        }, delayMs)
    }

    private fun updateWindowLayoutNow(view: View) {
        val params = windowParams ?: return
        layoutUpdatePending = false
        if (params.x == lastLayoutX && params.y == lastLayoutY && params.width == lastLayoutWidth && params.height == lastLayoutHeight) return
        lastLayoutX = params.x
        lastLayoutY = params.y
        lastLayoutWidth = params.width
        lastLayoutHeight = params.height
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { V2AppLog.w("V2BlindSpotOverlay", "update overlay layout failed", it) }
        applyPreviewTransform(textureView)
        updateMetricsText()
    }

    companion object {
        private const val MOVE_UPDATE_THRESHOLD_PX = 3
        private const val RESIZE_UPDATE_THRESHOLD_PX = 6
        private const val LAYOUT_UPDATE_INTERVAL_MS = 80L
        private const val METRICS_UPDATE_INTERVAL_MS = 1_000L
        private const val SIDE_SWITCH_ANIMATION_MS = 180L
    }
}
