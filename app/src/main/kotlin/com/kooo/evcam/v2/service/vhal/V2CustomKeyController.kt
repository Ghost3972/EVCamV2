package com.kooo.evcam.v2.service.vhal

import android.content.Context
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

class V2CustomKeyController(
    private val context: Context,
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val hideUi: () -> Unit,
    private val showUi: () -> Unit,
) {
    private var observer: V2VhalCustomKeyObserver? = null
    @Volatile private var config: V2SettingsSnapshot.CustomKey = V2SettingsRepository.customKeyConfig(context)

    fun start() {
        if (observer != null) return
        val current = config
        if (!current.enabled) {
            V2AppLog.i(TAG, "VHAL custom key observer skipped: disabled")
            return
        }
        observer = V2VhalCustomKeyObserver(
            buttonPropId = current.buttonPropId,
            listener = V2VhalCustomKeyObserver.Listener { handleValue4() }
        ).also { it.start() }
        V2AppLog.i(TAG, "VHAL custom key observer started buttonPropId=${current.buttonPropId}")
    }

    fun updateConfig(next: V2SettingsSnapshot.CustomKey) {
        val old = config
        config = next
        if (old != next) {
            V2AppLog.i(TAG, "custom key config updated enabled=${next.enabled} buttonPropId=${next.buttonPropId}")
        }
    }

    fun restart(next: V2SettingsSnapshot.CustomKey = V2SettingsRepository.customKeyConfig(context)) {
        V2AppLog.i(TAG, "refresh VHAL custom key observer")
        updateConfig(next)
        stop()
        start()
    }

    fun stop() {
        observer?.stop()
        observer = null
        V2AppLog.i(TAG, "VHAL custom key observer stopped")
    }

    private fun handleValue4() {
        handler.post {
            toggleUiFromCustomKey()
        }
    }

    private fun toggleUiFromCustomKey() {
        if (!config.enabled) {
            V2AppLog.i(TAG, "custom key toggle ignored: disabled")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "custom key toggle ignored: display off")
            return
        }
        if (isUiVisible()) {
            V2AppLog.i(TAG, "custom key value 4: hide UI")
            hideUi()
        } else {
            V2AppLog.i(TAG, "custom key value 4: show UI")
            showUi()
        }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
