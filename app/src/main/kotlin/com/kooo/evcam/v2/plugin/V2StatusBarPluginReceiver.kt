package com.kooo.evcam.v2.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.service.V2CameraForegroundService

class V2StatusBarPluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (V2CameraForegroundService.ACTION_TOGGLE_RECORDING_FROM_PLUGIN != action) return

        val serviceIntent = Intent(context, V2CameraForegroundService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (error: RuntimeException) {
            Log.e(TAG, "start service from plugin broadcast failed", error)
        }
    }

    companion object {
        private const val TAG = "V2StatusBarReceiver"
    }
}
