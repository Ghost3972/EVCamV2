package com.kooo.evcam.v2.ui.main

import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import com.kooo.evcam.databinding.ActivityV2MainA7Binding
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.service.V2MainPreviewServiceApi

internal class V2MainPreviewBinder(
    private val binding: ActivityV2MainA7Binding,
    private val mainHandler: Handler,
    private val service: () -> V2MainPreviewServiceApi?,
) {
    private val previewSizeLabels = Array(V2_CAMERA_SLOT_COUNT) { "--×--" }
    private val previewSurfaces = arrayOfNulls<Surface>(V2_CAMERA_SLOT_COUNT)
    private var compositePreviewHolder: SurfaceHolder? = null
    private var compositePreviewAttachState = CompositePreviewAttachState.DETACHED
    private var compositePreviewFirstFrameShown = false
    private var nativeFpsPolling = false

    private val compositePreviewCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            V2AppLog.i(TAG, "composite preview surface created valid=${holder.surface?.isValid == true}")
            resetCompositePreviewFirstFrameGate("surface_created")
            attachCompositePreviewSurface(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            V2AppLog.i(TAG, "composite preview surface changed size=${width}x$height format=$format")
            attachCompositePreviewSurface(holder)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            V2AppLog.i(TAG, "composite preview surface destroyed")
            resetCompositePreviewFirstFrameGate("surface_destroyed")
            detachCompositePreviewSurface(releaseSurface = false)
        }
    }

    private val nativeFpsPoller = object : Runnable {
        override fun run() {
            pollNativeCompositeFps()
            if (nativeFpsPolling) mainHandler.postDelayed(this, NATIVE_FPS_POLL_INTERVAL_MS)
        }
    }

    fun bindPreviews() {
        resetCompositePreviewFirstFrameGate("bind")
        binding.textureFront.setZOrderOnTop(false)
        binding.textureFront.holder.addCallback(compositePreviewCallback)
        updateCompositePreviewLabels(resetFps = true)
        startNativeFpsPolling("bind")
        binding.textureFront.post { attachCompositePreviewIfAvailable("post") }
        mainHandler.postDelayed({ attachCompositePreviewIfAvailable("delayed300") }, 300L)
        mainHandler.postDelayed({ attachCompositePreviewIfAvailable("delayed1000") }, 1_000L)
    }

    fun unbindPreviews() {
        stopNativeFpsPolling()
        binding.textureFront.holder.removeCallback(compositePreviewCallback)
        detachCompositePreviewSurface(releaseSurface = false)
    }

    fun releasePreviewSurfaces() {
        stopNativeFpsPolling()
        detachCompositePreviewSurface(releaseSurface = false)
    }

    fun updatePreviewPlaceholders(paused: Boolean) {
        binding.previewPlaceholderFront.visibility = if (paused) View.VISIBLE else View.GONE
        binding.previewWarmupFront.visibility = when {
            paused -> View.GONE
            compositePreviewFirstFrameShown -> View.GONE
            else -> View.VISIBLE
        }
    }

    private fun attachCompositePreviewIfAvailable(reason: String) {
        val holder = binding.textureFront.holder
        val surface = holder.surface
        V2AppLog.i(
            TAG,
            "composite preview bind check reason=$reason surfaceValid=${surface?.isValid == true} size=${binding.textureFront.width}x${binding.textureFront.height}"
        )
        if (surface?.isValid == true) attachCompositePreviewSurface(holder)
    }

    private fun attachCompositePreviewSurface(holder: SurfaceHolder) {
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            V2AppLog.w(TAG, "attachCompositePreviewSurface deferred: invalid holder surface")
            return
        }
        val existingSurface = previewSurfaces[COMPOSITE_PREVIEW_INDEX]
        if (compositePreviewHolder === holder && existingSurface === surface && existingSurface.isValid) {
            if (compositePreviewAttachState == CompositePreviewAttachState.ATTACHED) {
                V2AppLog.d(TAG, "attachCompositePreviewSurface skipped: already attached valid=${existingSurface.isValid}")
                updateCompositePreviewLabels(resetFps = false)
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
            updateCompositePreviewLabels(resetFps = true)
            return
        }
        detachCompositePreviewSurface(releaseSurface = false)
        compositePreviewHolder = holder
        previewSurfaces[COMPOSITE_PREVIEW_INDEX] = surface
        V2AppLog.d(TAG, "attachCompositePreviewSurface valid=${surface.isValid}")
        val previewService = service()
        if (previewService == null) {
            V2AppLog.w(TAG, "attachCompositePreviewSurface deferred: service unavailable")
            return
        }
        previewService.attachCompositePreviewSurface(surface)
        compositePreviewAttachState = CompositePreviewAttachState.ATTACHED
        updateCompositePreviewLabels(resetFps = true)
    }

    private fun detachCompositePreviewSurface(releaseSurface: Boolean = true) {
        V2AppLog.d(TAG, "detachCompositePreviewSurface hadSurface=${previewSurfaces[COMPOSITE_PREVIEW_INDEX] != null} release=$releaseSurface state=$compositePreviewAttachState")
        if (compositePreviewAttachState == CompositePreviewAttachState.ATTACHED) {
            service()?.detachCompositePreviewSurface()
            compositePreviewAttachState = CompositePreviewAttachState.DETACHED
        }
        if (releaseSurface) {
            previewSurfaces[COMPOSITE_PREVIEW_INDEX]?.release()
        }
        previewSurfaces[COMPOSITE_PREVIEW_INDEX] = null
        compositePreviewHolder = null
    }

    private fun resetCompositePreviewFirstFrameGate(reason: String) {
        compositePreviewFirstFrameShown = false
        V2AppLog.d(TAG, "composite preview first frame gate reset reason=$reason")
        updatePreviewPlaceholders(service()?.isPreviewPausedByAvoidance() == true)
    }

    private fun revealCompositePreviewIfNeeded(reason: String) {
        if (compositePreviewFirstFrameShown) return
        compositePreviewFirstFrameShown = true
        binding.previewWarmupFront.visibility = View.GONE
        V2AppLog.i(TAG, "composite preview first frame shown reason=$reason")
    }

    private fun updateCompositePreviewLabels(resetFps: Boolean) {
        updatePreviewPlaceholders(service()?.isPreviewPausedByAvoidance() == true)
        previewSizeLabels[COMPOSITE_PREVIEW_INDEX] = service()?.compositePreviewSizeLabel() ?: "--×--"
        if (resetFps) {
            binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n-- fps"
        }
    }

    private fun startNativeFpsPolling(reason: String) {
        V2AppLog.d(TAG, "native composite fps polling started reason=$reason")
        nativeFpsPolling = true
        mainHandler.removeCallbacks(nativeFpsPoller)
        mainHandler.post(nativeFpsPoller)
    }

    private fun stopNativeFpsPolling() {
        nativeFpsPolling = false
        mainHandler.removeCallbacks(nativeFpsPoller)
    }

    private fun pollNativeCompositeFps() {
        val previewService = service() ?: return
        val frames = previewService.compositePreviewRenderedFrames().coerceAtLeast(0L)
        if (frames > 0) revealCompositePreviewIfNeeded("native_frame")
        val fpsMilli = previewService.compositePreviewFpsMilli().coerceAtLeast(0L)
        val fpsText = if (fpsMilli > 0L) formatNativeFps(fpsMilli) else "-- fps"
        binding.fpsFront.text = "${previewSizeLabels[COMPOSITE_PREVIEW_INDEX]}\n$fpsText"
    }

    private fun formatNativeFps(fpsMilli: Long): String {
        val roundedTenths = (fpsMilli.coerceAtLeast(0L) + 50L) / 100L
        return "${roundedTenths / 10}.${roundedTenths % 10} fps"
    }

    private enum class CompositePreviewAttachState {
        DETACHED,
        ATTACHED,
    }

    private companion object {
        private const val TAG = "V2MainActivity"
        private const val COMPOSITE_PREVIEW_INDEX = 0
        private const val NATIVE_FPS_POLL_INTERVAL_MS = 500L
    }
}
