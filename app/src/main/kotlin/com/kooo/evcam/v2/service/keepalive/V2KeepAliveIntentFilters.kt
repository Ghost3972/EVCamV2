package com.kooo.evcam.v2.service.keepalive

import android.content.Intent
import android.content.IntentFilter

internal object V2KeepAliveIntentFilters {
    fun keepAlive(includeTimeTick: Boolean): IntentFilter = IntentFilter().apply {
        if (includeTimeTick) addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(Intent.ACTION_BATTERY_LOW)
        addAction(Intent.ACTION_BATTERY_OKAY)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_LOCALE_CHANGED)
        addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        addAction(Intent.ACTION_HEADSET_PLUG)
        addAction("android.bluetooth.adapter.action.STATE_CHANGED")
        addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
        addAction("android.bluetooth.device.action.ACL_CONNECTED")
        addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
        addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        addAction("android.net.conn.CONNECTIVITY_CHANGE")
        addAction("android.net.wifi.STATE_CHANGE")
        addAction("android.net.wifi.SCAN_RESULTS")
        addAction("android.media.AUDIO_BECOMING_NOISY")
        addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
        addAction(V2KeepAliveReceiver.ACTION_KEEP_ALIVE)
    }

    fun media(): IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED)
        addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_REMOVED)
        addAction(Intent.ACTION_MEDIA_EJECT)
        addDataScheme("file")
    }

    fun packageUpdates(): IntentFilter = IntentFilter().apply {
        addDataScheme("package")
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
    }
}
