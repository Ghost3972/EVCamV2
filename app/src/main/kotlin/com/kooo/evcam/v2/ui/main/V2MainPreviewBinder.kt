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
    private var compositePreviewAttachState = CompositePreviewAttachState.DETACHED

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
        detachCompositePreviewSurface(releaseSurface = true)
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
        val existingSurface = previewSurfaces[COMPOSITE_PREVIEW_INDEX]
        if (compositePreviewSurfaceTexture === surfaceTexture && existingSurface?.isValid == true) {
            if (compositePreviewAttachState == CompositePreviewAttachState.ATTACHED) {
                V2AppLog.d(TAG, "attachCompositePreviewSurface skipped: already attached valid=${existingSurface.isValid}")
                updateCompositePreviewLabels()
                return
            }
            V2AppLog.d(TAG, "attachCompositePreviewSurface reuse existing valid=${existingSurface.isValid} state=$compositePreviewAttachState")
            val previewService = service()
            if (previewService == null) {
                V2AppLog.w(TAG, "attachCompositePreviewSurface deferred: service unavailable")
                return
            }
            previewService.attachCompositePreviewSurface(existingSurface)
            compositePreviewAttachState = CompositePreviewAttachState.ATTACHED
            updateCompositePreviewLabels()
            return
        }
        detachCompositePreviewSurface(releaseSurface = true)
        val surface = Surface(surfaceTexture)
        compositePreviewSurfaceTexture = surfaceTexture
        previewSurfaces[COMPOSITE_PREVIEW_INDEX] = surface
        V2AppLog.d(TAG, "attachCompositePreviewSurface valid=${surface.isValid}")
        val previewService = service()
        if (previewService == null) {
            V2AppLog.w(TAG, "attachCompositePreviewSurface deferred: service unavailable")
            return
        }
        previewService.attachCompositePreviewSurface(surface)
        compositePreviewAttachState = CompositePreviewAttachState.ATTACHED
        updateCompositePreviewLabels()
    }

    private fun detachCompositePreviewSurface(releaseSurface: Boolean = true) {
        V2AppLog.d(TAG, "detachCompositePreviewSurface hadSurface=${previewSurfaces[COMPOSITE_PREVIEW_INDEX] != null} release=$releaseSurface state=$compositePreviewAttachState")
        if (compositePreviewAttachState == CompositePreviewAttachState.ATTACHED) {
            service()?.detachCompositePreviewSurface()
            compositePreviewAttachState = CompositePreviewAttachState.DETACHED
        }
        if (releaseSurface) {
            previewSurfaces[COMPOSITE_PREVIEW_INDEX]?.release()
            previewSurfaces[COMPOSITE_PREVIEW_INDEX] = null
            compositePreviewSurfaceTexture = null
        }
    }

    private fun updateCompositePreviewLabels() {
        updatePreviewPlaceholders(service()?.isPreviewPausedByAvoidance() == true)
        previewSizeLabels[COMPOSITE_PREVIEW_INDEX] = service()?.compositePreviewSizeLabel() ?: "--×--"
        binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n-- fps"
    }

    private enum class CompositePreviewAttachState {
        DETACHED,
        ATTACHED,
    }

    private companion object {
        private const val TAG = "V2MainActivity"
        private const val COMPOSITE_PREVIEW_INDEX = 0
    }
}
