package com.kooo.evcam.v2.ui.blindspot

import android.app.ActivityOptions
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2SettingsRepository
import java.lang.ref.WeakReference

class V2BlindSpotSmallWindowActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private val style by lazy { V2BlindSpotOverlayStyle(this) }
    private val metrics = V2BlindSpotOverlayMetrics { index -> service?.previewRenderedFrames(index) ?: 0L }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: V2CameraForegroundService? = null
    private var surfaceController: V2BlindSpotPreviewSurfaceController? = null
    private var textureView: TextureView? = null
    private var metricsView: TextView? = null
    private var side: String = "left"
    private var cameraIndex: Int = -1
    private var rotationDegrees = 0
    private var correction = V2BlindSpotCorrection()
    private var windowSwapped = false
    private var firstFrameShown = false
    private var finishRequestedByService = false

    private val metricsRunnable = object : Runnable {
        override fun run() {
            updateMetricsText()
            mainHandler.postDelayed(this, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        }
    }

    private val revealFallbackRunnable = Runnable {
        if (!isFinishing && !isDestroyed) revealWindow("timeout")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? V2CameraForegroundService.LocalBinder
            service = local?.service()
            surfaceController = V2BlindSpotPreviewSurfaceController(
                attachPreview = { index, surface -> service?.attachBlindSpotPreviewSurface(index, surface) },
                detachPreview = { index -> service?.detachBlindSpotPreviewSurface(index) },
                previewInputSize = { index -> service?.previewInputSize(index) },
            )
            attachPreviewIfReady()
            V2AppLog.i(TAG, "service connected side=$side index=$cameraIndex")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            surfaceController?.detach()
            surfaceController = null
            service = null
            V2AppLog.w(TAG, "service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        removeMainPreviewSmallWindowTasks()
        configureWindow()
        buildContent()
        applyIntent(intent)
        bindService(Intent(this, V2CameraForegroundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        mainHandler.postDelayed(revealFallbackRunnable, REVEAL_FALLBACK_MS)
        mainHandler.postDelayed(metricsRunnable, V2BlindSpotOverlayMetrics.UPDATE_INTERVAL_MS)
        V2AppLog.i(TAG, "onCreate side=$side index=$cameraIndex")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        attachPreviewIfReady()
        V2AppLog.i(TAG, "onNewIntent side=$side index=$cameraIndex")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(revealFallbackRunnable)
        mainHandler.removeCallbacks(metricsRunnable)
        surfaceController?.detach()
        runCatching { unbindService(serviceConnection) }
        if (activeActivity?.get() === this) activeActivity = null
        if (!finishRequestedByService) V2CameraServiceCommands.hideBlindSpotPreview(this)
        super.onDestroy()
        V2AppLog.i(TAG, "onDestroy side=$side index=$cameraIndex serviceClose=$finishRequestedByService")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeFromUser()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachPreviewIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyPreviewTransform()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        metrics.onFrameDisplayed()
        if (!firstFrameShown) revealWindow("first_frame")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceController?.detach()
        firstFrameShown = false
        return true
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.attributes = window.attributes.apply { alpha = 0f }
    }

    private fun removeMainPreviewSmallWindowTasks() {
        runCatching {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.orEmpty().forEach { appTask ->
                val taskInfo = appTask.taskInfo ?: return@forEach
                val topClass = taskInfo.topActivity?.className.orEmpty()
                val baseClass = taskInfo.baseActivity?.className.orEmpty()
                val taskText = taskInfo.toString()
                val isFlymeSmallWindow = taskText.contains("flyme-mini-window", ignoreCase = true)
                val isBlindSpotTask = topClass == javaClass.name || baseClass == javaClass.name
                if (isFlymeSmallWindow && !isBlindSpotTask) {
                    appTask.finishAndRemoveTask()
                    V2AppLog.w(TAG, "removed non-blind-spot small window task top=$topClass base=$baseClass task=$taskText")
                }
            }
        }.onFailure {
            V2AppLog.w(TAG, "remove non-blind-spot small window task failed", it)
        }
    }

    private fun buildContent() {
        val root = FrameLayout(this).apply {
            background = style.roundedBackground()
            clipToOutline = true
        }
        textureView = TextureView(this).apply {
            isOpaque = true
            surfaceTextureListener = this@V2BlindSpotSmallWindowActivity
        }
        root.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        metricsView = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = true
            setLineSpacing(style.dp(8f).toFloat(), 1f)
            setPadding(style.dp(22f), style.dp(10f), style.dp(22f), style.dp(14f))
            background = style.pillBackground(0.46f)
        }
        root.addView(
            metricsView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, style.dp(100f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = style.edgeControlMargin()
                bottomMargin = style.bottomControlMargin()
            }
        )
        setContentView(root)
    }

    private fun applyIntent(intent: Intent?) {
        val nextSide = if (intent?.getStringExtra(EXTRA_SIDE) == "right") "right" else "left"
        var nextCameraIndex = intent?.getIntExtra(EXTRA_CAMERA_INDEX, -1) ?: -1
        val fallbackIndex = service?.previewIndexForPosition(nextSide)
        if (nextCameraIndex < 0 && fallbackIndex != null) nextCameraIndex = fallbackIndex
        val targetChanged = side != nextSide || cameraIndex != nextCameraIndex
        side = nextSide
        cameraIndex = nextCameraIndex
        loadTransformForSide()
        applyPreviewTransform()
        if (targetChanged || !firstFrameShown) {
            resetMetrics()
            firstFrameShown = false
            window.attributes = window.attributes.apply { alpha = 0f }
            mainHandler.removeCallbacks(revealFallbackRunnable)
            mainHandler.postDelayed(revealFallbackRunnable, REVEAL_FALLBACK_MS)
        } else {
            updateMetricsText()
        }
    }

    private fun attachPreviewIfReady() {
        val texture = textureView ?: return
        if (cameraIndex < 0 || !texture.isAvailable) return
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceController?.attach(cameraIndex, surfaceTexture)
        applyPreviewTransform()
    }

    private fun closeFromUser() {
        V2CameraServiceCommands.hideBlindSpotPreview(this)
        finish()
    }

    private fun finishFromService() {
        finishRequestedByService = true
        finish()
    }

    private fun revealWindow(reason: String) {
        if (firstFrameShown && window.attributes.alpha == 1f) return
        firstFrameShown = true
        window.attributes = window.attributes.apply { alpha = 1f }
        V2AppLog.i(TAG, "reveal small window reason=$reason side=$side index=$cameraIndex")
    }

    private fun loadTransformForSide() {
        val config = V2SettingsRepository.blindSpotOverlayConfig(this, side, 0, 0, 0, 0)
        rotationDegrees = config.rotation
        correction = config.correction
        windowSwapped = V2BlindSpotTransform.isCloserToPortrait(previewRotationDegrees())
    }

    private fun applyPreviewTransform() {
        V2BlindSpotTransform.apply(
            texture = textureView,
            overlayRotationDegrees = rotationDegrees,
            correction = correction,
            windowSwapped = windowSwapped,
            previewSize = if (cameraIndex >= 0) service?.previewInputSize(cameraIndex) else null,
        )
    }

    private fun resetMetrics() {
        metrics.reset(null, metricsView)
        val width = textureView?.width ?: 0
        val height = textureView?.height ?: 0
        metricsView?.text = "${width}x$height\n-- fps"
    }

    private fun updateMetricsText() {
        val width = textureView?.width ?: 0
        val height = textureView?.height ?: 0
        metrics.updateText(width, height, cameraIndex, metricsView)
    }

    private fun previewRotationDegrees(): Float = V2BlindSpotTransform.effectiveRotation(rotationDegrees, correction)

    companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
        private const val EXTRA_SIDE = "com.kooo.evcam.v2.extra.BLIND_SPOT_SIDE"
        private const val EXTRA_CAMERA_INDEX = "com.kooo.evcam.v2.extra.BLIND_SPOT_CAMERA_INDEX"
        private const val EXTRA_WINDOW_MODE = "windowMode"
        private const val START_WINDOW_MODE_KEY = "start_windowmode"
        private const val START_WINDOW_MODE_SMALL = 1
        private const val WINDOW_MODE_FLOATING = 1
        private const val REVEAL_FALLBACK_MS = 2_000L

        @Volatile private var activeActivity: WeakReference<V2BlindSpotSmallWindowActivity>? = null

        fun show(context: Context, side: String, cameraIndex: Int) {
            val intent = Intent(context, V2BlindSpotSmallWindowActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_SIDE, side)
                putExtra(EXTRA_CAMERA_INDEX, cameraIndex)
                putExtra(EXTRA_WINDOW_MODE, WINDOW_MODE_FLOATING)
            }
            val options = ActivityOptions.makeBasic().toBundle().apply {
                putInt(START_WINDOW_MODE_KEY, START_WINDOW_MODE_SMALL)
            }
            context.startActivity(intent, options)
        }

        fun finishActiveFromService() {
            val activity = activeActivity?.get() ?: return
            Handler(Looper.getMainLooper()).post { activity.finishFromService() }
        }
    }
}
