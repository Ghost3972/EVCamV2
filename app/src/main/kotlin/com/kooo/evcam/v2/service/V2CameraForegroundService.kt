package com.kooo.evcam.v2.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.service.lifecycle.V2CameraServiceRuntime

class V2CameraForegroundService : Service() {
    companion object {
        const val ACTION_AUTO_START_RECORDING = "com.kooo.evcam.v2.action.AUTO_START_RECORDING"
        const val ACTION_REFRESH_CUSTOM_KEY = "com.kooo.evcam.v2.action.REFRESH_CUSTOM_KEY"
        const val ACTION_REFRESH_BLIND_SPOT = "com.kooo.evcam.v2.action.REFRESH_BLIND_SPOT"
        const val ACTION_REFRESH_FISHEYE = "com.kooo.evcam.v2.action.REFRESH_FISHEYE"
        const val ACTION_REFRESH_WAKE_LOCK = "com.kooo.evcam.v2.action.REFRESH_WAKE_LOCK"
        const val ACTION_SETTINGS_CHANGED = "com.kooo.evcam.v2.action.SETTINGS_CHANGED"
        const val ACTION_SHOW_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.SHOW_FISHEYE_PREVIEW"
        const val ACTION_HIDE_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.HIDE_FISHEYE_PREVIEW"
        const val ACTION_SHOW_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.SHOW_BLIND_SPOT_PREVIEW"
        const val ACTION_HIDE_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.HIDE_BLIND_SPOT_PREVIEW"
        const val ACTION_TOGGLE_RECORDING_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_TOGGLE_RECORDING"
        const val ACTION_START_EMERGENCY_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_START_EMERGENCY"
        const val EXTRA_CAMERA_INDEX = "camera_index"
        const val EXTRA_SIDE = "side"
        const val EXTRA_SETTINGS_CATEGORY = "settings_category"
        internal const val AUTO_START_RECORDING_DELAY_MS = 0L
        const val EMERGENCY_RECORDING_DURATION_MS = 15_000L

        @Volatile var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun service() = this@V2CameraForegroundService
    }

    private val binder = LocalBinder()
    private lateinit var runtime: V2CameraServiceRuntime

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        V2AppLog.init(this)
        runtime = V2CameraServiceRuntime(this)
        runtime.start()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2BroadcastLogger.logServiceStart("V2CameraService", intent, flags, startId)
        runtime.recordStartCommand(intent?.action)
        runtime.route(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.destroy()
        isRunning = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::runtime.isInitialized) runtime.handleTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    fun toggleRecording(): Boolean = runtime.toggleRecording()

    fun isRecording(): Boolean = runtime.isRecording()

    fun isNormalRecording(): Boolean = runtime.isNormalRecording()

    fun statusText(): String = runtime.statusText()

    fun isPreviewPausedByAvoidance(): Boolean = runtime.isPreviewPausedByAvoidance()

    fun ensureReadyAfterPermissions() {
        runtime.ensureReadyAfterPermissions()
    }

    fun previewInputSizeLabel(index: Int): String = runtime.previewInputSizeLabel(index)

    fun previewInputSize(index: Int): Size? = runtime.previewInputSize(index)

    fun compositePreviewSizeLabel(): String = runtime.compositePreviewSizeLabel()

    fun attachCompositePreviewSurface(surface: Surface) {
        runtime.attachCompositePreviewSurface(surface)
    }

    fun detachCompositePreviewSurface() {
        runtime.detachCompositePreviewSurface()
    }

    fun attachPreviewSurface(index: Int, surface: Surface) {
        runtime.attachPreviewSurface(index, surface)
    }

    fun detachPreviewSurface(index: Int) {
        runtime.detachPreviewSurface(index)
    }

    internal fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        runtime.attachFisheyePreviewSurface(index, surface)
    }

    internal fun detachFisheyePreviewSurface(index: Int) {
        runtime.detachFisheyePreviewSurface(index)
    }

    internal fun canShowFisheyePreview(index: Int): Boolean = runtime.canShowFisheyePreview(index)

    fun startRecording() {
        runtime.startRecording()
    }

    fun stopRecording() {
        runtime.stopRecording()
    }

    fun startEmergencyRecording(
        durationMs: Long = EMERGENCY_RECORDING_DURATION_MS,
        onStateChanged: ((Boolean) -> Unit)? = null,
    ): Boolean {
        return runtime.startEmergencyRecording(durationMs, onStateChanged)
    }

    fun shutdownFromUi() {
        runtime.shutdownFromUi()
    }

    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        runtime.setUiStatusListener(listener)
    }

    fun setUiEmergencyRecordingListener(listener: ((Boolean, Long) -> Unit)?) {
        runtime.setUiEmergencyRecordingListener(listener)
    }

    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        runtime.setUiVisibility(visible, hideListener)
    }
}
