package com.kooo.evcam.v2.service.commands

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.settings.V2SettingsCategory

object V2CameraServiceCommands {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, V2CameraForegroundService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, V2CameraForegroundService::class.java))
    }

    fun autoStartRecording(context: Context) = startAction(context, V2CameraServiceContract.ACTION_AUTO_START_RECORDING)

    fun refreshCustomKey(context: Context) = notifySettingsChanged(context, V2SettingsCategory.CUSTOM_KEY)

    fun refreshBlindSpot(context: Context) = notifySettingsChanged(context, V2SettingsCategory.BLIND_SPOT)

    fun refreshFisheye(context: Context) = notifySettingsChanged(context, V2SettingsCategory.FISHEYE)

    fun refreshWakeLock(context: Context) = notifySettingsChanged(context, V2SettingsCategory.WAKE_LOCK)

    fun notifySettingsChanged(context: Context, category: String = V2SettingsCategory.ALL) =
        startAction(context, V2CameraServiceContract.ACTION_SETTINGS_CHANGED) {
            putExtra(V2CameraServiceContract.EXTRA_SETTINGS_CATEGORY, category)
        }

    fun notifySettingsChangedIfRunning(context: Context, category: String = V2SettingsCategory.ALL) {
        if (!V2CameraForegroundService.isRunning) return
        notifySettingsChanged(context, category)
    }

    fun showFisheyePreview(context: Context, cameraIndex: Int) = startAction(context, V2CameraServiceContract.ACTION_SHOW_FISHEYE_PREVIEW) {
        putExtra(V2CameraServiceContract.EXTRA_CAMERA_INDEX, cameraIndex)
    }

    fun hideFisheyePreview(context: Context) = startAction(context, V2CameraServiceContract.ACTION_HIDE_FISHEYE_PREVIEW)

    fun showBlindSpotPreview(context: Context, side: String) = startAction(context, V2CameraServiceContract.ACTION_SHOW_BLIND_SPOT_PREVIEW) {
        putExtra(V2CameraServiceContract.EXTRA_SIDE, side)
    }

    fun hideBlindSpotPreview(context: Context) = startAction(context, V2CameraServiceContract.ACTION_HIDE_BLIND_SPOT_PREVIEW)

    fun startAction(context: Context, action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(context, V2CameraForegroundService::class.java).apply {
            this.action = action
            configure()
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
