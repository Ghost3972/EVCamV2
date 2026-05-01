package com.kooo.evcam.v2.service

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Executor

object V2CameraSessionFactory {
    fun createPreviewSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val executor = Executor { command -> handler.post(command) }
            device.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigs, executor, callback)
            )
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, callback, handler)
        }
    }
}
