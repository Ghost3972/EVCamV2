package com.kooo.evcam.v2.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.service.runtime.V2CameraServiceRuntime

class V2CameraForegroundService : Service(), V2CameraServiceUiApi, V2BlindSpotPreviewServiceApi {
    companion object {
        @Volatile var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun uiApi(): V2CameraServiceUiApi = this@V2CameraForegroundService
        fun blindSpotApi(): V2BlindSpotPreviewServiceApi = this@V2CameraForegroundService
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

    override fun toggleRecording(): Boolean = runtime.toggleRecording()

    fun isRecording(): Boolean = runtime.isRecording()

    override fun isNormalRecording(): Boolean = runtime.isNormalRecording()

    fun statusText(): String = runtime.statusText()

    override fun isPreviewPausedByAvoidance(): Boolean = runtime.isPreviewPausedByAvoidance()

    override fun ensureReadyAfterPermissions() {
        runtime.ensureReadyAfterPermissions()
    }

    fun previewInputSizeLabel(index: Int): String = runtime.previewInputSizeLabel(index)

    override fun previewInputSize(index: Int): Size? = runtime.previewInputSize(index)

    override fun compositePreviewSizeLabel(): String = runtime.compositePreviewSizeLabel()

    override fun attachCompositePreviewSurface(surface: Surface) {
        runtime.attachCompositePreviewSurface(surface)
    }

    override fun detachCompositePreviewSurface() {
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

    override fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        runtime.attachBlindSpotPreviewSurface(index, surface)
    }

    override fun detachBlindSpotPreviewSurface(index: Int) {
        runtime.detachBlindSpotPreviewSurface(index)
    }

    override fun previewIndexForPosition(position: String): Int? = runtime.previewIndexForPosition(position)

    override fun previewRenderedFrames(index: Int): Long = runtime.previewRenderedFrames(index)

    override fun compositePreviewRenderedFrames(): Long = runtime.compositePreviewRenderedFrames()

    override fun compositePreviewFpsMilli(): Long = runtime.compositePreviewFpsMilli()

    override fun startRecording() {
        runtime.startRecording()
    }

    override fun stopRecording() {
        runtime.stopRecording()
    }

    override fun shutdownFromUi() {
        runtime.shutdownFromUi()
    }

    override fun setUiStatusListener(listener: ((String) -> Unit)?) {
        runtime.setUiStatusListener(listener)
    }

    override fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)?) {
        runtime.setUiVisibility(visible, hideListener)
    }
}
