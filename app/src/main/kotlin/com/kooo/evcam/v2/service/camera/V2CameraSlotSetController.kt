package com.kooo.evcam.v2.service.camera

import android.hardware.camera2.CameraManager
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraSpec

internal class V2CameraSlotSetController(
    private val cameraManager: CameraManager,
    private val pipelineHandle: Long,
    private val specs: List<V2CameraSpec>,
    private val slots: List<V2CameraSlot>,
    private val slotLifecycle: V2CameraSlotLifecycle,
    private val previewSurfaceController: V2CameraPreviewSurfaceController,
    private val cameraAccessAllowed: () -> Boolean,
    private val released: () -> Boolean,
    private val bumpCameraGeneration: () -> Unit,
    private val publishStatus: () -> Unit,
) {
    fun startCameras() {
        if (released()) {
            V2AppLog.w(TAG, "startCameras skipped: engine released")
            return
        }
        if (pipelineHandle == 0L) {
            V2AppLog.e(TAG, "startCameras skipped: native compositor unavailable")
            return
        }
        if (!cameraAccessAllowed()) {
            V2AppLog.w(TAG, "startCameras skipped: screen is off")
            return
        }
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i(TAG, "startCameras slots=${specs.joinToString { "${it.label}:${it.cameraId}" }}")
        slots.forEach { slot ->
            slot.ensureInputSurface(cameraManager)
            slotLifecycle.openCamera(slot)
        }
        previewSurfaceController.startPreviewWorkerIfNeeded()
        V2AppLog.perf(TAG, "startCameras_schedule", SystemClock.elapsedRealtime() - startedMs, "requestedSlots=${slots.size}")
    }

    fun stopCameras() {
        bumpCameraGeneration()
        val startedMs = SystemClock.elapsedRealtime()
        V2AppLog.i(TAG, "stopCameras openSlots=${slots.count { it.nativeCameraHandle != 0L }}")
        previewSurfaceController.detachAttachedPreviewsForCameraStop()
        slots.forEach { slot -> slot.close() }
        V2AppLog.perf(TAG, "stopCameras", SystemClock.elapsedRealtime() - startedMs)
        publishStatus()
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
    }
}
