package com.kooo.evcam.v2.service.camera

import android.os.Handler
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor

internal class V2CameraPreviewSurfaceController(
    private val nativeCompositor: V2NativeCompositor,
    private val pipelineHandle: Long,
    private val slots: List<V2CameraSlot>,
    private val statusFormatter: V2CameraStatusFormatter,
    private val renderHandler: Handler,
    private val previewMaxFps: Int,
    private val cameraAccessAllowed: () -> Boolean,
    private val released: () -> Boolean,
    private val publishStatus: () -> Unit,
    private val publishStatusIfNeeded: () -> Unit,
) {
    private var previewRenderingEnabled = true
    private var compositePreviewAttached = false

    fun attachCompositePreviewSurface(surface: Surface) {
        if (released() || pipelineHandle == 0L) return
        if (!cameraAccessAllowed()) {
            V2AppLog.w(TAG, "attach composite preview skipped: screen is off")
            return
        }
        V2AppLog.d(TAG, "attach composite preview")
        stopPreviewWorkerForSurfaceMutation("attachCompositePreview")
        if (!nativeCompositor.attachCompositePreview(surface)) {
            compositePreviewAttached = false
            V2AppLog.e(TAG, "attach composite preview failed: ${nativeCompositor.lastError()}")
            startPreviewWorkerIfNeeded()
            publishStatus()
            return
        }
        compositePreviewAttached = true
        startPreviewWorkerIfNeeded()
        publishStatus()
    }

    fun detachCompositePreviewSurface() {
        if (pipelineHandle == 0L) return
        stopPreviewWorkerForSurfaceMutation("detachCompositePreview")
        compositePreviewAttached = false
        V2AppLog.d(TAG, "detach composite preview")
        nativeCompositor.detachCompositePreview()
        startPreviewWorkerIfNeeded()
        publishStatus()
    }

    fun attachPreviewSurface(index: Int, surface: Surface, applyFisheye: Boolean = true, applyNativeTransform: Boolean = true) {
        val slot = slots.getOrNull(index) ?: return
        if (released() || pipelineHandle == 0L) return
        if (!cameraAccessAllowed()) {
            V2AppLog.w(TAG, "attach preview skipped: screen is off ${slot.spec.name}/${slot.spec.cameraId}")
            return
        }

        V2AppLog.d(TAG, "attach preview ${slot.spec.name}/${slot.spec.cameraId}")
        stopPreviewWorkerForSurfaceMutation("attachPreview")
        if (!nativeCompositor.attachPreview(index, surface, applyFisheye, applyNativeTransform)) {
            slot.previewAttached = false
            V2AppLog.e(TAG, "attach preview failed ${slot.spec.name}/${slot.spec.cameraId}: ${nativeCompositor.lastError()}")
            startPreviewWorkerIfNeeded()
            publishStatus()
            return
        }
        slot.previewAttached = true
        startPreviewWorkerIfNeeded()
        publishStatus()
    }

    fun detachPreviewSurface(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        if (pipelineHandle == 0L) return
        stopPreviewWorkerForSurfaceMutation("detachPreview")
        slot.previewAttached = false
        V2AppLog.d(TAG, "detach preview ${slot.spec.name}/${slot.spec.cameraId}")
        nativeCompositor.detachPreview(index)
        startPreviewWorkerIfNeeded()
        statusFormatter.resetSlot(slot.index, slot.frameSignals, slot.renderedFrames)
        publishStatus()
    }

    fun detachAttachedPreviewsForCameraStop() {
        val attachedPreviewIndexes = slots.filter { it.previewAttached }.map { it.index }.toIntArray()
        if (attachedPreviewIndexes.isNotEmpty() || compositePreviewAttached) {
            stopPreviewWorkerForSurfaceMutation("stopCameras")
        }
        if (attachedPreviewIndexes.isNotEmpty()) {
            runCatching { nativeCompositor.detachPreviews(attachedPreviewIndexes) }
                .onFailure { V2AppLog.e(TAG, "batch detach preview failed", it) }
        }
        slots.forEach { slot ->
            if (slot.previewAttached) slot.previewAttached = false
        }
    }

    fun setPreviewRenderingEnabled(enabled: Boolean) {
        if (previewRenderingEnabled == enabled) return
        previewRenderingEnabled = enabled
        V2AppLog.d(TAG, "previewRenderingEnabled=$enabled")
        if (enabled) {
            runCatching { nativeCompositor.startPreviewWorker(previewMaxFps) }
        } else {
            runCatching { nativeCompositor.stopPreviewWorker() }
            renderHandler.post {
                if (previewRenderingEnabled) return@post
                slots.forEach { slot ->
                    slot.lastRenderMs = 0L
                    slot.lastPreviewError = "paused"
                }
                publishStatusIfNeeded()
            }
        }
    }

    fun startInitialPreviewWorkerIfEnabled() {
        if (previewRenderingEnabled) runCatching { nativeCompositor.startPreviewWorker(previewMaxFps) }
    }

    fun startPreviewWorkerIfNeeded() {
        if (!previewRenderingEnabled || released() || pipelineHandle == 0L) return
        if (!compositePreviewAttached && slots.none { it.previewAttached }) return
        runCatching { nativeCompositor.startPreviewWorker(previewMaxFps) }
            .onFailure { V2AppLog.w(TAG, "restart preview worker after surface mutation failed", it) }
    }

    fun stopPreviewWorkerForRelease() {
        runCatching { nativeCompositor.stopPreviewWorker() }
    }

    private fun stopPreviewWorkerForSurfaceMutation(reason: String) {
        if (pipelineHandle == 0L) return
        runCatching { nativeCompositor.stopPreviewWorker(1_000L) }
            .onFailure { V2AppLog.w(TAG, "stop preview worker before surface mutation failed reason=$reason", it) }
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
    }
}
