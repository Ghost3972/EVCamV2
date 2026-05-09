package com.kooo.evcam.v2.service.commands

import android.content.Intent
import com.kooo.evcam.v2.service.display.V2DisplayPowerActions
import com.kooo.evcam.v2.settings.V2SettingsCategory

internal object V2CameraServiceActionParser {
    fun fromIntent(intent: Intent?): V2CameraServiceAction? = fromFields(
        action = intent?.action,
        cameraIndex = intent?.getIntExtra(V2CameraServiceContract.EXTRA_CAMERA_INDEX, 0) ?: 0,
        side = intent?.getStringExtra(V2CameraServiceContract.EXTRA_SIDE),
        settingsCategory = intent?.getStringExtra(V2CameraServiceContract.EXTRA_SETTINGS_CATEGORY),
    )

    fun fromFields(
        action: String?,
        cameraIndex: Int = 0,
        side: String? = null,
        settingsCategory: String? = null,
    ): V2CameraServiceAction? = when {
        action == V2CameraServiceContract.ACTION_AUTO_START_RECORDING -> V2CameraServiceAction.AutoStartRecording
        action == V2CameraServiceContract.ACTION_SETTINGS_CHANGED ->
            V2CameraServiceAction.SettingsChanged(settingsCategory)
        action == V2CameraServiceContract.ACTION_REFRESH_CUSTOM_KEY ->
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.CUSTOM_KEY)
        action == V2CameraServiceContract.ACTION_REFRESH_BLIND_SPOT ->
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.BLIND_SPOT)
        action == V2CameraServiceContract.ACTION_REFRESH_FISHEYE ->
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.FISHEYE)
        action == V2CameraServiceContract.ACTION_REFRESH_WAKE_LOCK ->
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.WAKE_LOCK)
        action == V2CameraServiceContract.ACTION_SHOW_FISHEYE_PREVIEW ->
            V2CameraServiceAction.ShowFisheyePreview(cameraIndex)
        action == V2CameraServiceContract.ACTION_HIDE_FISHEYE_PREVIEW ->
            V2CameraServiceAction.HideFisheyePreview
        action == V2CameraServiceContract.ACTION_SHOW_BLIND_SPOT_PREVIEW ->
            V2CameraServiceAction.ShowBlindSpotPreview(side ?: V2CameraServiceContract.DEFAULT_BLIND_SPOT_SIDE)
        action == V2CameraServiceContract.ACTION_HIDE_BLIND_SPOT_PREVIEW ->
            V2CameraServiceAction.HideBlindSpotPreview
        action == V2CameraServiceContract.ACTION_TOGGLE_RECORDING_FROM_PLUGIN ->
            V2CameraServiceAction.ToggleRecordingFromPlugin
        action != null && V2DisplayPowerActions.isDisplayOff(action) ->
            V2CameraServiceAction.DisplayOff(action)
        action != null && V2DisplayPowerActions.isDisplayOn(action) ->
            V2CameraServiceAction.DisplayOn(action)
        else -> null
    }
}
