package com.kooo.evcam.v2.ui.main

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.kooo.evcam.databinding.ActivityV2MainA7Binding
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.ui.preview.V2PreviewFpsCounter

internal class V2MainPreviewBinder(
    private val binding: ActivityV2MainA7Binding,
    private val mainHandler: Handler,
    private val service: () -> V2CameraForegroundService?,
) {
    private val fpsCounters = Array(V2_CAMERA_SLOT_COUNT) { V2PreviewFpsCounter() }
    private val previewSizeLabels = Array(V2_CAMERA_SLOT_COUNT) { "--×--" }
    private val previewSurfaces = arrayOfNulls<Surface>(V2_CAMERA_SLOT_COUNT)
    private var compositePreviewSurfaceTexture: SurfaceTexture? = null

    fun bindPreviews() {
        binding.textureFront.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                V2AppLog.i(TAG, "composite preview surface available size=${width}x$height")
                fpsCounters[COMPOSITE_PREVIEW_INDEX].reset()
                previewSizeLabels[COMPOSITE_PREVIEW_INDEX] = service()?.compositePreviewSizeLabel() ?: "--×--"
                binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n-- fps"
                attachCompositePreviewSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                V2AppLog.i(TAG, "composite preview surface destroyed")
                detachCompositePreviewSurface(releaseSurface = true)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                fpsCounters[COMPOSITE_PREVIEW_INDEX].onFrame()?.let { fps ->
                    binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n$fps fps"
                }
            }
        }
        if (binding.textureFront.isAvailable && binding.textureFront.surfaceTexture != null) {
            attachCompositePreviewSurface(binding.textureFront.surfaceTexture!!)
        }
        binding.textureFront.post { attachCompositePreviewIfAvailable("post") }
        mainHandler.postDelayed({ attachCompositePreviewIfAvailable("delayed300") }, 300L)
        mainHandler.postDelayed({ attachCompositePreviewIfAvailable("delayed1000") }, 1_000L)
    }

    fun unbindPreviews() {
        detachCompositePreviewSurface(releaseSurface = false)
    }

    fun releasePreviewSurfaces() {
        detachCompositePreviewSurface(releaseSurface = true)
    }

    fun updatePreviewPlaceholders(paused: Boolean) {
        binding.previewPlaceholderFront.visibility = if (paused) View.VISIBLE else View.GONE
    }

    private fun attachCompositePreviewIfAvailable(reason: String) {
        val surfaceTexture = binding.textureFront.surfaceTexture
        V2AppLog.i(
            TAG,
            "composite preview bind check reason=$reason available=${binding.textureFront.isAvailable} surface=${surfaceTexture != null} size=${binding.textureFront.width}x${binding.textureFront.height}"
        )
        if (binding.textureFront.isAvailable && surfaceTexture != null) attachCompositePreviewSurface(surfaceTexture)
    }

    private fun attachCompositePreviewSurface(surfaceTexture: SurfaceTexture) {
        if (compositePreviewSurfaceTexture === surfaceTexture && previewSurfaces[COMPOSITE_PREVIEW_INDEX]?.isValid == true) {
            V2AppLog.d(TAG, "reattachCompositePreviewSurface existing valid=${previewSurfaces[COMPOSITE_PREVIEW_INDEX]?.isValid}")
            service()?.attachCompositePreviewSurface(previewSurfaces[COMPOSITE_PREVIEW_INDEX]!!)
            return
        }
        detachCompositePreviewSurface(releaseSurface = true)
        val surface = Surface(surfaceTexture)
        compositePreviewSurfaceTexture = surfaceTexture
        previewSurfaces[COMPOSITE_PREVIEW_INDEX] = surface
        V2AppLog.d(TAG, "attachCompositePreviewSurface valid=${surface.isValid}")
        service()?.attachCompositePreviewSurface(surface)
        updatePreviewPlaceholders(service()?.isPreviewPausedByAvoidance() == true)
        previewSizeLabels[COMPOSITE_PREVIEW_INDEX] = service()?.compositePreviewSizeLabel() ?: "--×--"
        binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n-- fps"
    }

    private fun detachCompositePreviewSurface(releaseSurface: Boolean = true) {
        V2AppLog.d(TAG, "detachCompositePreviewSurface hadSurface=${previewSurfaces[COMPOSITE_PREVIEW_INDEX] != null} release=$releaseSurface")
        service()?.detachCompositePreviewSurface()
        if (releaseSurface) {
            previewSurfaces[COMPOSITE_PREVIEW_INDEX]?.release()
            previewSurfaces[COMPOSITE_PREVIEW_INDEX] = null
            compositePreviewSurfaceTexture = null
        }
    }

    private companion object {
        private const val TAG = "V2MainActivity"
        private const val COMPOSITE_PREVIEW_INDEX = 0
    }
}
