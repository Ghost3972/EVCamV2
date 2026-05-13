package com.kooo.evcam.v2.service.commands

object V2CameraServiceContract {
    const val ACTION_AUTO_START_RECORDING = "com.kooo.evcam.v2.action.AUTO_START_RECORDING"
    const val ACTION_REFRESH_CUSTOM_KEY = "com.kooo.evcam.v2.action.REFRESH_CUSTOM_KEY"
    const val ACTION_REFRESH_BLIND_SPOT = "com.kooo.evcam.v2.action.REFRESH_BLIND_SPOT"
    const val ACTION_REFRESH_FISHEYE = "com.kooo.evcam.v2.action.REFRESH_FISHEYE"
    const val ACTION_REFRESH_WAKE_LOCK = "com.kooo.evcam.v2.action.REFRESH_WAKE_LOCK"
    const val ACTION_SETTINGS_CHANGED = "com.kooo.evcam.v2.action.SETTINGS_CHANGED"
    const val ACTION_SHOW_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.SHOW_FISHEYE_PREVIEW"
    const val ACTION_HIDE_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.HIDE_FISHEYE_PREVIEW"
    const val ACTION_SHOW_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.SHOW_BLIND_SPOT_PREVIEW"
    const val ACTION_HIDE_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.HIDE_BLIND_SPOT_PREVIEW"
    const val ACTION_TOGGLE_RECORDING_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_TOGGLE_RECORDING"

    const val EXTRA_CAMERA_INDEX = "camera_index"
    const val EXTRA_SIDE = "side"
    const val EXTRA_SETTINGS_CATEGORY = "settings_category"
    const val DEFAULT_BLIND_SPOT_SIDE = "left"
}
