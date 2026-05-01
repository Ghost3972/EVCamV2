package com.kooo.evcam.v2.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import com.ecarx.venus.displaypower.IDisplayPowerService
import com.ecarx.venus.displaypower.IDisplayPowerServiceListener
import com.kooo.evcam.v2.log.V2AppLog

internal class V2EcarxDisplayPowerClient(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (displayId: Int, state: Int, source: String) -> Unit
) {
    companion object {
        const val DISPLAY_ID_CSD = 0
        const val POWER_ALL_OFF = 0
        const val BACKLIGHT_OFF_TOUCH_ON = 1
        const val POWER_ALL_ON = 2

        private const val TAG = "V2DisplayPowerClient"
        private const val PACKAGE_NAME = "com.ecarx.venus.displaypower.service"
        private const val SERVICE_NAME = "com.ecarx.venus.displaypower.service.DisplayPowerService"
        private const val ACTION_START_SERVICE = "ecarx.venus.intent.action.START_DISPLAYPOWER_SERVICE"
        private const val REBIND_DELAY_MS = 1_000L
    }

    @Volatile private var service: IDisplayPowerService? = null
    private var bound = false
    private var stopped = false
    private val rebindRunnable = Runnable { start() }

    private val listener = object : IDisplayPowerServiceListener.Stub() {
        override fun notifyDisplayPowerStateChanged(displayId: Int, newStatus: Int) {
            handler.post { onStateChanged(displayId, newStatus, "callback") }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val displayPowerService = IDisplayPowerService.Stub.asInterface(binder)
            service = displayPowerService
            V2AppLog.i(TAG, "connected name=$name")
            runCatching { displayPowerService.subscribe(listener) }
                .onSuccess { V2AppLog.i(TAG, "subscribe result=$it") }
                .onFailure { V2AppLog.e(TAG, "subscribe failed", it) }
            queryCurrentState("connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            V2AppLog.w(TAG, "disconnected name=$name")
            service = null
            bound = false
            scheduleRebind("disconnected")
        }
    }

    fun start() {
        stopped = false
        if (bound) return
        handler.removeCallbacks(rebindRunnable)
        val intent = Intent(ACTION_START_SERVICE).apply {
            component = ComponentName(PACKAGE_NAME, SERVICE_NAME)
        }
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { V2AppLog.e(TAG, "bind failed", it) }
            .getOrDefault(false)
        V2AppLog.i(TAG, "bind requested result=$bound")
        if (!bound) scheduleRebind("bind_failed")
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(rebindRunnable)
        service?.let { displayPowerService ->
            runCatching { displayPowerService.unsubscribe(listener) }
                .onSuccess { V2AppLog.i(TAG, "unsubscribe result=$it") }
                .onFailure { V2AppLog.e(TAG, "unsubscribe failed", it) }
        }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    fun queryCurrentState(reason: String) {
        val displayPowerService = service ?: return
        runCatching { displayPowerService.getDisplayPowerState(DISPLAY_ID_CSD) }
            .onSuccess { state ->
                V2AppLog.i(TAG, "current CSD state=$state reason=$reason")
                handler.post { onStateChanged(DISPLAY_ID_CSD, state, "query:$reason") }
            }
            .onFailure { V2AppLog.e(TAG, "query current state failed reason=$reason", it) }
    }

    private fun scheduleRebind(reason: String) {
        if (stopped) return
        handler.removeCallbacks(rebindRunnable)
        V2AppLog.w(TAG, "schedule rebind reason=$reason delayMs=$REBIND_DELAY_MS")
        handler.postDelayed(rebindRunnable, REBIND_DELAY_MS)
    }
}
