package com.kooo.evcam.v2.ui.blindspot

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.kooo.evcam.v2.log.V2AppLog

internal class V2BlindSpotPreviewSurfaceController(
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
    private val previewInputSize: (Int) -> Size?,
) {
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var attachedPreviewIndex: Int = -1

    fun isAttached(index: Int): Boolean = attachedPreviewIndex == index && previewSurface?.isValid == true

    fun attach(index: Int, surfaceTexture: SurfaceTexture) {
        val startedMs = SystemClock.elapsedRealtime()
        configurePreviewBufferSize(index, surfaceTexture)
        if (attachedPreviewIndex == index && previewSurfaceTexture == surfaceTexture && previewSurface?.isValid == true) return
        detach()
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        previewSurfaceTexture = surfaceTexture
        attachedPreviewIndex = index
        attachPreview(index, surface)
        V2AppLog.perf("V2BlindSpotPerf", "attachPreview", SystemClock.elapsedRealtime() - startedMs, "index=$index valid=${surface.isValid}")
    }

    fun refresh(index: Int, textureView: TextureView?) {
        val surfaceTexture = textureView?.surfaceTexture ?: return
        if (textureView.isAvailable != true) return
        attach(index, surfaceTexture)
    }

    fun detach() {
        val index = attachedPreviewIndex
        if (index >= 0) detachPreview(index)
        previewSurface?.release()
        previewSurface = null
        previewSurfaceTexture = null
        attachedPreviewIndex = -1
    }

    private fun configurePreviewBufferSize(index: Int, surfaceTexture: SurfaceTexture) {
        val size = previewInputSize(index) ?: return
        if (size.width <= 0 || size.height <= 0) return
        val startedMs = SystemClock.elapsedRealtime()
        runCatching { surfaceTexture.setDefaultBufferSize(size.width, size.height) }
            .onSuccess {
                V2AppLog.perf(
                    "V2BlindSpotPerf",
                    "setBufferSize",
                    SystemClock.elapsedRealtime() - startedMs,
                    "index=$index size=${size.width}x${size.height}"
                )
            }
            .onFailure {
                V2AppLog.w(
                    "V2BlindSpotOverlay",
                    "set preview buffer size failed index=$index size=${size.width}x${size.height}",
                    it
                )
            }
    }
}
