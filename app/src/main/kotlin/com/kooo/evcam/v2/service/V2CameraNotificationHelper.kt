package com.kooo.evcam.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.kooo.evcam.R

internal class V2CameraNotificationHelper(private val service: V2CameraForegroundService) {
    private val notificationManager by lazy {
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var channelCreated = false

    fun startForeground(text: String) {
        service.startForeground(NOTIFICATION_ID, build(text))
    }

    fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, build(text))
    }

    private fun build(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("EVCam V2")
            .setContentText(text)
            .setOngoing(true)
            .addExtras(flymeStatusIconExtras(text))
            .build()
    }

    private fun flymeStatusIconExtras(text: String): Bundle {
        return Bundle().apply {
            putBoolean(FLAG_STATUS_ICON, true)
            putInt(FLAG_STATUS_ICON_ID, STATUS_BAR_PLUGIN_ID)
            putString(FLAG_STATUS_ICON_DESCRIBE, "EVCam")
            val iconRes = if (text.contains("rec=ON")) R.drawable.v2_status_bar_icon_on else R.drawable.v2_status_bar_icon_off
            putParcelable(FLAG_STATUS_ICON_ICON, Icon.createWithResource(service, iconRes))
            putParcelable(FLAG_STATUS_ICON_PRESSED_ICON, Icon.createWithResource(service, iconRes))
            putBoolean(FLAG_STATUS_ICON_HIDE, false)
            putBoolean(FLAG_STATUS_ICON_IS_PICK_ON, true)
            putInt(FLAG_STATUS_ICON_SPACE_X, 1)
            putInt(FLAG_STATUS_ICON_RANK, STATUS_BAR_PLUGIN_ID)
            putString(Notification.EXTRA_TEXT, text)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelCreated) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V2 Camera", NotificationManager.IMPORTANCE_LOW)
        )
        channelCreated = true
    }

    private companion object {
        private const val CHANNEL_ID = "v2_camera"
        private const val NOTIFICATION_ID = 2001
        private const val STATUS_BAR_PLUGIN_ID = 132
        private const val FLAG_STATUS_ICON = "flag_status_icon_notification"
        private const val FLAG_STATUS_ICON_ID = "flag_status_icon_id"
        private const val FLAG_STATUS_ICON_DESCRIBE = "flag_status_icon_describe"
        private const val FLAG_STATUS_ICON_ICON = "flag_status_icon_icon"
        private const val FLAG_STATUS_ICON_PRESSED_ICON = "flag_status_icon_pressed_icon"
        private const val FLAG_STATUS_ICON_HIDE = "flag_status_icon_hide"
        private const val FLAG_STATUS_ICON_IS_PICK_ON = "flag_status_icon_is_pick_on"
        private const val FLAG_STATUS_ICON_SPACE_X = "flag_status_icon_space_x"
        private const val FLAG_STATUS_ICON_RANK = "flag_status_icon_rank"
    }
}
