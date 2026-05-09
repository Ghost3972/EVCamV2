package com.kooo.evcam.v2.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraServiceUiApi
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands

internal class V2MainCameraServiceBinder(
    private val activity: AppCompatActivity,
    private val onConnected: (V2CameraServiceUiApi?) -> Unit,
) {
    var service: V2CameraServiceUiApi? = null
        private set

    var isBound: Boolean = false
        private set

    private var bindingService = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? V2CameraForegroundService.LocalBinder)?.uiApi()
            isBound = true
            bindingService = false
            V2AppLog.i(TAG, "service connected name=$name serviceReady=${service != null}")
            onConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            V2AppLog.w(TAG, "service disconnected name=$name")
            isBound = false
            bindingService = false
            service = null
        }
    }

    fun startAndBind() {
        V2AppLog.i(TAG, "startAndBindService bound=$isBound binding=$bindingService")
        val intent = Intent(activity, V2CameraForegroundService::class.java)
        V2CameraServiceCommands.start(activity)
        if (!isBound && !bindingService) {
            bindingService = true
            if (!activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bindingService = false
            }
        }
    }

    fun unbind() {
        if (!isBound) return
        activity.unbindService(connection)
        isBound = false
        bindingService = false
        service = null
    }

    private companion object {
        private const val TAG = "V2MainActivity"
    }
}
