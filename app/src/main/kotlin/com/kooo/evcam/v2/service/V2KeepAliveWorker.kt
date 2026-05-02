package com.kooo.evcam.v2.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kooo.evcam.v2.settings.V2SettingsRepository

class V2KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        V2KeepAliveStatus.recordWorker(context)
        V2KeepAliveStatus.recordTrigger(context, "worker", "periodic")
        val keepAlivePolicy = V2SettingsRepository.keepAlivePolicy(context)
        val startupPolicy = V2SettingsRepository.startupPolicy(context)
        if (!keepAlivePolicy.enabled) return Result.success()
        if (!startupPolicy.autoStartOnBoot) return Result.success()
        if (!hasRequiredPermissions(context)) return Result.retry()

        return runCatching {
            V2CameraServiceCommands.start(context)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return cameraGranted
    }
}
