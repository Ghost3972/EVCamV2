package com.kooo.evcam.v2.ui.blindspot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2BlindSpotPreviewServiceApi
import com.kooo.evcam.v2.service.V2CameraForegroundService

internal class V2BlindSpotSmallWindowServiceBinder(
    private val activity: AppCompatActivity,
    private val currentTarget: () -> V2BlindSpotSmallWindowTarget,
    private val onConnected: () -> Unit,
) {
    private var service: V2BlindSpotPreviewServiceApi? = null
    private var surfaceController: V2BlindSpotPreviewSurfaceController? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? V2CameraForegroundService.LocalBinder
            service = local?.blindSpotApi()
            surfaceController = V2BlindSpotPreviewSurfaceController(
                attachPreview = { index, surface -> service?.attachBlindSpotPreviewSurface(index, surface) },
                detachPreview = { index -> service?.detachBlindSpotPreviewSurface(index) },
                previewInputSize = { index -> service?.previewInputSize(index) },
            )
            onConnected()
            val target = currentTarget()
            V2AppLog.i(TAG, "service connected side=${target.side} index=${target.cameraIndex}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detach()
            surfaceController = null
            service = null
            V2AppLog.w(TAG, "service disconnected")
        }
    }

    fun bind() {
        activity.bindService(
            Intent(activity, V2CameraForegroundService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        detach()
        runCatching { activity.unbindService(connection) }
    }

    fun previewRenderedFrames(index: Int): Long = service?.previewRenderedFrames(index) ?: 0L

    fun previewIndexForPosition(side: String): Int? = service?.previewIndexForPosition(side)

    fun attachIfReady(cameraIndex: Int, textureView: TextureView?) {
        val texture = textureView ?: return
        if (cameraIndex < 0 || !texture.isAvailable) return
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceController?.attach(cameraIndex, surfaceTexture)
    }

    fun detach() {
        surfaceController?.detach()
    }

    private companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
    }
}
