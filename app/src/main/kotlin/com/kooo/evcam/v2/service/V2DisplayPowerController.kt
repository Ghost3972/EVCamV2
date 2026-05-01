package com.kooo.evcam.v2.service

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

class V2DisplayPowerController(
    private val service: V2CameraForegroundService,
    private val handler: Handler,
    systemInteractive: Boolean,
    private val onDisplayOff: (String?) -> Unit,
    private val onDisplayOn: (String?) -> Unit,
) {
    @Volatile private var displayPowerOn = V2DisplayPowerState.initialValue(systemInteractive)
    private val coordinator = V2DisplayPowerCoordinator(
        service = service,
        onDisplayOff = { action -> onDisplayOff(action) },
        onDisplayOn = { action -> onDisplayOn(action) },
    )
    private var ecarxClient: V2EcarxDisplayPowerClient? = null

    fun isOn(): Boolean = displayPowerOn

    fun register() {
        coordinator.register()
        startEcarxClient()
    }

    fun unregister() {
        stopEcarxClient()
        coordinator.unregister()
    }

    fun markOff(action: String?) {
        displayPowerOn = V2DisplayPowerState.updateFromAction(action) ?: false
    }

    fun markOn(action: String?) {
        displayPowerOn = V2DisplayPowerState.updateFromAction(action) ?: true
    }

    fun markOnIfAlreadyOn(action: String?) {
        V2DisplayPowerState.updateFromAction(action) ?: V2DisplayPowerState.updateFromSystem(true)
    }

    fun queryCurrentState(reason: String) {
        ecarxClient?.queryCurrentState(reason)
    }

    private fun startEcarxClient() {
        if (ecarxClient != null) return
        ecarxClient = V2EcarxDisplayPowerClient(
            context = service,
            handler = handler,
            onStateChanged = { displayId, state, source -> handleEcarxState(displayId, state, source) }
        ).also { it.start() }
    }

    private fun stopEcarxClient() {
        ecarxClient?.stop()
        ecarxClient = null
    }

    private fun handleEcarxState(displayId: Int, state: Int, source: String) {
        if (displayId != V2EcarxDisplayPowerClient.DISPLAY_ID_CSD) {
            V2AppLog.i(TAG, "ignore ECarX display power displayId=$displayId state=$state source=$source")
            return
        }
        val powerOn = when (state) {
            V2EcarxDisplayPowerClient.POWER_ALL_ON -> true
            V2EcarxDisplayPowerClient.POWER_ALL_OFF,
            V2EcarxDisplayPowerClient.BACKLIGHT_OFF_TOUCH_ON -> false
            else -> {
                V2AppLog.w(TAG, "ignore ECarX display power unknown state=$state source=$source")
                return
            }
        }
        if (powerOn == displayPowerOn) {
            V2DisplayPowerState.updateFromSystem(powerOn)
            V2AppLog.i(TAG, "ECarX display power unchanged displayOn=$powerOn state=$state source=$source")
            return
        }
        if (powerOn) {
            V2AppLog.i(TAG, "ECarX display power ON state=$state source=$source: restore cameras")
            onDisplayOn("ecarx_display_power:$source:state=$state")
        } else {
            V2AppLog.w(TAG, "ECarX display power OFF state=$state source=$source: release cameras before STR")
            onDisplayOff("ecarx_display_power:$source:state=$state")
        }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
