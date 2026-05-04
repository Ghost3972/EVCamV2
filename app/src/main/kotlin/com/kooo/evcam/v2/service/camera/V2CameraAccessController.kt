package com.kooo.evcam.v2.service.camera

import com.kooo.evcam.v2.log.V2AppLog

internal class V2CameraAccessController(
    private val slots: List<V2CameraSlot>,
    private val recordingController: V2CameraRecordingController,
    private val cameraAccessAllowed: () -> Boolean,
    private val setCameraAccessAllowed: (Boolean) -> Unit,
    private val bumpCameraGeneration: () -> Unit,
    private val startCameras: () -> Unit,
    private val stopRecording: () -> Unit,
    private val stopCameras: () -> Unit,
    private val publishStatus: () -> Unit,
) {
    fun setAllowed(allowed: Boolean) {
        if (cameraAccessAllowed() == allowed) {
            if (!allowed) stopRecordingAndReleaseCameras("camera_access_already_disabled")
            return
        }
        setCameraAccessAllowed(allowed)
        bumpCameraGeneration()
        V2AppLog.i(TAG, "cameraAccessAllowed=$allowed")
        if (!allowed) {
            stopRecordingAndReleaseCameras("camera_access_disabled")
        } else {
            startCameras()
        }
        publishStatus()
    }

    fun stopRecordingAndReleaseCameras(reason: String) {
        if (cameraAccessAllowed()) {
            setCameraAccessAllowed(false)
            bumpCameraGeneration()
            V2AppLog.i(TAG, "cameraAccessAllowed=false reason=$reason")
        }
        val openBefore = slots.count { it.nativeCameraHandle != 0L }
        V2AppLog.w(TAG, "screen-off release begin reason=$reason recording=${recordingController.isRecording} openSlots=$openBefore")
        stopRecording()
        stopCameras()
        val openAfter = slots.count { it.nativeCameraHandle != 0L }
        V2AppLog.w(TAG, "screen-off release complete reason=$reason recording=${recordingController.isRecording} openSlots=$openAfter")
        publishStatus()
    }

    private companion object {
        private const val TAG = "V2CameraEngine"
    }
}
