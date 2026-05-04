package com.kooo.evcam.v2.service.camera

import android.hardware.camera2.CameraManager
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative

internal class V2CameraSlotLifecycle(
    private val cameraManager: CameraManager,
    private val pipelineHandle: Long,
    private val cameraAccessAllowed: () -> Boolean,
    private val released: () -> Boolean,
    private val cameraGeneration: () -> Int,
    private val publishStatus: () -> Unit,
) {
    fun restartPreviewAfterRecordingStop(slot: V2CameraSlot) {
        if (!cameraAccessAllowed()) return
        slot.ensureInputSurface(cameraManager)
        if (slot.nativeCameraHandle == 0L) {
            V2AppLog.w(TAG, "preview recovery opening camera ${slot.spec.name}/${slot.spec.cameraId}")
            openCamera(slot)
        }
    }

    fun openCamera(slot: V2CameraSlot) {
        if (released()) return
        if (!cameraAccessAllowed()) return
        val generation = cameraGeneration()
        val cameraId = slot.spec.cameraId
        val availableIds = V2CameraDeviceCapabilities.cameraIds(cameraManager)
        if (!availableIds.contains(cameraId)) {
            V2AppLog.e(TAG, "openCamera skipped: cameraId=$cameraId unavailable available=$availableIds spec=${slot.spec.name}")
            return
        }
        if (slot.inputSurface == null) {
            V2AppLog.e(TAG, "openCamera skipped: input surface missing ${slot.spec.name}/$cameraId")
            return
        }
        if (!openNativeCamera(slot, cameraId, generation)) {
            slot.lastPreviewError = GlesNative.getLastError()
            V2AppLog.e(TAG, "NDK openCamera failed ${slot.spec.name}/$cameraId: ${slot.lastPreviewError}")
            publishStatus()
        }
    }

    private fun openNativeCamera(slot: V2CameraSlot, cameraId: String, generation: Int): Boolean {
        if (slot.nativeCameraHandle != 0L) return true
        val inputSurface = slot.inputSurface ?: return false
        if (!GlesNative.isLoaded) return false
        val startedMs = SystemClock.elapsedRealtime()
        val handle = runCatching { GlesNative.createNativeCameraPreview(cameraId, inputSurface, pipelineHandle, slot.index) }
            .onFailure { V2AppLog.e(TAG, "NDK openCamera crashed ${slot.spec.name}/$cameraId", it) }
            .getOrDefault(0L)
        if (released() || !cameraAccessAllowed() || generation != cameraGeneration() || slot.inputSurface == null) {
            if (handle != 0L) runCatching { GlesNative.releaseNativeCameraPreview(handle) }
            return true
        }
        if (handle == 0L) {
            slot.lastPreviewError = GlesNative.getLastError()
            return false
        }
        slot.nativeCameraHandle = handle
        slot.lastPreviewError = "无"
        V2AppLog.perf(TAG, "openNativeCamera", SystemClock.elapsedRealtime() - startedMs, "slot=${slot.spec.name}/${slot.spec.cameraId}")
        publishStatus()
        return true
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
    }
}
