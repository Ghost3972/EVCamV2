package com.kooo.evcam.v2.service.preview

import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.vhal.V2VhalTurnSignalObserver
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2BlindSpotSignalObserver(
    private val onSignal: (String, Boolean) -> Unit,
) {
    private var observer: V2VhalTurnSignalObserver? = null

    fun start(config: V2SettingsSnapshot.BlindSpot) {
        if (observer != null) return
        if (!config.enabled) {
            V2AppLog.i(TAG, "blind spot observer skipped: disabled")
            return
        }
        observer = V2VhalTurnSignalObserver(
            config.turnSignalPropId,
            config.leftValue,
            config.rightValue,
            config.offValue,
        ) { side, on -> onSignal(side, on) }.also { it.start() }
        V2AppLog.i(
            TAG,
            "blind spot observer started propId=${config.turnSignalPropId} left=${config.leftValue} right=${config.rightValue}"
        )
    }

    fun stop() {
        observer?.stop()
        observer = null
        V2AppLog.i(TAG, "blind spot observer stopped")
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
