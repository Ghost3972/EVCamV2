package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import java.lang.ref.WeakReference

class V2BlindSpotSmallWindowActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transformStore = V2BlindSpotSmallWindowTransformStore(this)
    private val serviceBinder = V2BlindSpotSmallWindowServiceBinder(
        activity = this,
        currentTarget = { target },
        onConnected = { attachPreviewIfReady() },
    )
    private val metrics = V2BlindSpotOverlayMetrics { index -> serviceBinder.previewRenderedFrames(index) }
    private val revealController by lazy {
        V2BlindSpotSmallWindowRevealController(
            window = window,
            handler = mainHandler,
            isClosed = { isFinishing || isDestroyed },
            currentTarget = { target },
            afterReveal = { reason -> flymeChrome.disable("reveal/$reason") },
        )
    }
    private var textureView: TextureView? = null
    private var target = V2BlindSpotSmallWindowTarget(side = "left", cameraIndex = -1)
    private var transform = V2BlindSpotSmallWindowTransform(0, V2BlindSpotCorrection())
    private var finishRequestedByService = false
    private val flymeChrome = V2BlindSpotFlymeWindowChromeController(this) { reason -> closeFromUser(reason) }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            updateMetricsText()
            mainHandler.postDelayed(this, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        V2BlindSpotSmallWindowTaskCleaner.removeOtherFlymeSmallWindowTasks(this)
        flymeChrome.disable("create")
        configureWindow()
        buildContent()
        applyIntent(intent)
        serviceBinder.bind()
        revealController.scheduleFallback()
        mainHandler.postDelayed(metricsRunnable, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        mainHandler.postDelayed({ flymeChrome.disable("create/delayed") }, FLYME_CAPTION_DELAY_MS)
        V2AppLog.i(TAG, "onCreate side=${target.side} index=${target.cameraIndex}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        attachPreviewIfReady()
        flymeChrome.disable("new_intent")
        V2AppLog.i(TAG, "onNewIntent side=${target.side} index=${target.cameraIndex}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) flymeChrome.disable("focus")
    }

    override fun onResume() {
        super.onResume()
        flymeChrome.disable("resume")
    }

    override fun onDestroy() {
        revealController.cancel()
        mainHandler.removeCallbacks(metricsRunnable)
        serviceBinder.unbind()
        if (activeActivity?.get() === this) activeActivity = null
        if (!finishRequestedByService) V2CameraServiceCommands.hideBlindSpotPreview(this)
        super.onDestroy()
        V2AppLog.i(TAG, "onDestroy side=${target.side} index=${target.cameraIndex} serviceClose=$finishRequestedByService")
    }

    @Deprecated("Use OnBackPressedDispatcher when this activity is migrated.")
    override fun onBackPressed() {
        closeFromUser("back")
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachPreviewIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyPreviewTransform()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        metrics.onFrameDisplayed()
        if (!revealController.revealed) revealController.reveal("first_frame")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        serviceBinder.detach()
        revealController.markFrameLost()
        return true
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.attributes = window.attributes.apply { alpha = 0f }
    }

    private fun buildContent() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        textureView = TextureView(this).apply {
            isOpaque = true
            surfaceTextureListener = this@V2BlindSpotSmallWindowActivity
        }
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)
        flymeChrome.disable("content")
    }

    private fun applyIntent(intent: Intent?) {
        val nextTarget = V2BlindSpotSmallWindowIntents.targetFrom(intent) { side ->
            serviceBinder.previewIndexForPosition(side)
        }
        val targetChanged = target != nextTarget
        target = nextTarget
        transform = transformStore.load(target.side)
        applyPreviewTransform()
        if (targetChanged || !revealController.revealed) {
            resetMetrics()
            revealController.resetAndHide()
        } else {
            updateMetricsText()
        }
    }

    private fun attachPreviewIfReady() {
        serviceBinder.attachIfReady(target.cameraIndex, textureView)
        applyPreviewTransform()
    }

    private fun closeFromUser(reason: String = "user") {
        V2AppLog.i(TAG, "close from user reason=$reason")
        V2CameraServiceCommands.hideBlindSpotPreview(this)
        finish()
    }

    private fun finishFromService() {
        finishRequestedByService = true
        finish()
    }

    private fun applyPreviewTransform() {
        V2BlindSpotTransform.apply(
            texture = textureView,
            baseRotationDegrees = transform.rotationDegrees,
            correction = transform.correction,
        )
    }

    private fun resetMetrics() {
        metrics.reset(null, null)
    }

    private fun updateMetricsText() {
        val width = textureView?.width ?: 0
        val height = textureView?.height ?: 0
        metrics.updateText(width, height, target.cameraIndex, null)
    }

    companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
        private const val FLYME_CAPTION_DELAY_MS = 700L

        @Volatile private var activeActivity: WeakReference<V2BlindSpotSmallWindowActivity>? = null

        fun show(context: Context, side: String, cameraIndex: Int) {
            val normalizedSide = V2BlindSpotSmallWindowIntents.normalizeSide(side)
            val intent = V2BlindSpotSmallWindowIntents.startIntent(context, normalizedSide, cameraIndex)
            val options = V2BlindSpotSmallWindowIntents.startOptions()
            V2AppLog.i(TAG, "start master blind spot small window side=$normalizedSide index=$cameraIndex systemDefaultWindow=true")
            context.startActivity(intent, options)
        }

        fun finishActiveFromService() {
            val activity = activeActivity?.get() ?: return
            Handler(Looper.getMainLooper()).post { activity.finishFromService() }
        }

    }
}
