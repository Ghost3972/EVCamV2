package com.kooo.evcam.v2.service

import android.content.Context
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2CustomKeySettings

class V2CustomKeyController(
    private val context: Context,
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val hideUi: () -> Unit,
    private val showUi: () -> Unit,
) {
    private var observer: V2VhalCustomKeyObserver? = null

    fun start() {
        if (observer != null) return
        if (!V2CustomKeySettings.isEnabled(context)) {
            V2AppLog.i(TAG, "VHAL custom key observer skipped: disabled")
            return
        }
        val buttonPropId = V2CustomKeySettings.buttonPropId(context)
        observer = V2VhalCustomKeyObserver(
            buttonPropId = buttonPropId,
            listener = V2VhalCustomKeyObserver.Listener { handleValue4() }
        ).also { it.start() }
        V2AppLog.i(TAG, "VHAL custom key observer started buttonPropId=$buttonPropId")
    }

    fun restart() {
        V2AppLog.i(TAG, "refresh VHAL custom key observer")
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
