package com.kooo.evcam.v2.service.keepalive

import android.content.Intent

internal object V2KeepAliveActionClassifier {
    fun reasonFor(action: String): String = when (action) {
        Intent.ACTION_SCREEN_ON -> "screen_on"
        Intent.ACTION_SCREEN_OFF -> "screen_off"
        Intent.ACTION_USER_PRESENT -> "user_present"
        Intent.ACTION_POWER_CONNECTED -> "power_connected"
        Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
        Intent.ACTION_BATTERY_LOW -> "battery_low"
        Intent.ACTION_BATTERY_OKAY -> "battery_okay"
        Intent.ACTION_MEDIA_MOUNTED -> "media_mounted"
        Intent.ACTION_MEDIA_UNMOUNTED -> "media_unmounted"
        Intent.ACTION_MEDIA_REMOVED -> "media_removed"
        Intent.ACTION_MEDIA_EJECT -> "media_eject"
        Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
        Intent.ACTION_TIME_CHANGED -> "time_changed"
        Intent.ACTION_DATE_CHANGED -> "date_changed"
        Intent.ACTION_LOCALE_CHANGED -> "locale_changed"
        Intent.ACTION_AIRPLANE_MODE_CHANGED -> "airplane_mode"
        Intent.ACTION_HEADSET_PLUG -> "headset_plug"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
        V2KeepAliveReceiver.ACTION_KEEP_ALIVE -> "manual_keep_alive"
        else -> action.substringAfterLast('.')
    }

    fun isQuietAction(action: String): Boolean = action == Intent.ACTION_BATTERY_CHANGED ||
        action == "android.net.wifi.SCAN_RESULTS" ||
        action == Intent.ACTION_PACKAGE_ADDED ||
        action == Intent.ACTION_PACKAGE_REPLACED

    fun shouldStartThroughActivity(action: String): Boolean =
        action == Intent.ACTION_MY_PACKAGE_REPLACED

    fun isStorageChangeAction(action: String): Boolean = action == Intent.ACTION_MEDIA_MOUNTED ||
        action == Intent.ACTION_MEDIA_UNMOUNTED ||
        action == Intent.ACTION_MEDIA_REMOVED ||
        action == Intent.ACTION_MEDIA_EJECT ||
        action == "android.hardware.usb.action.USB_DEVICE_ATTACHED" ||
        action == "android.hardware.usb.action.USB_DEVICE_DETACHED"
}
