package com.kooo.evcam.v2.service.preview

import android.content.Context
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

class V2BlindSpotController(
    private val context: Context,
    private val handler: Handler,
    isDisplayPowerOn: () -> Boolean,
    isUiVisible: () -> Boolean,
    shouldAvoidWindow: () -> Boolean,
    avoidanceTarget: () -> String?,
    previewIndexForSide: (String) -> Int?,
    previewDescription: (Int) -> String,
    restoreMainPreview: (Int) -> Unit,
    hideFisheyePreview: () -> Unit,
    hideUi: () -> Unit,
) {
    @Volatile private var config: V2SettingsSnapshot.BlindSpot = V2SettingsRepository.blindSpotConfig(context)
    private val windowCoordinator = V2BlindSpotWindowCoordinator(
        context = context,
        handler = handler,
        isDisplayPowerOn = isDisplayPowerOn,
        isUiVisible = isUiVisible,
        shouldAvoidWindow = shouldAvoidWindow,
        avoidanceTarget = avoidanceTarget,
        previewIndexForSide = previewIndexForSide,
        previewDescription = previewDescription,
        restoreMainPreview = restoreMainPreview,
        hideFisheyePreview = hideFisheyePreview,
        hideUi = hideUi,
    )
    private val signalObserver = V2BlindSpotSignalObserver { side, on -> handleTurnSignal(side, on) }

    val activeCameraIndex: Int
        get() = windowCoordinator.activeCameraIndex

    fun startObserver() {
        signalObserver.start(config)
    }

    fun updateConfig(next: V2SettingsSnapshot.BlindSpot) {
        val old = config
        config = next
        if (!next.enabled) hide()
        if (old != next) {
            V2AppLog.i(TAG, "blind spot config updated enabled=${next.enabled} propId=${next.turnSignalPropId} correction=${next.correctionEnabled}")
        }
    }

    fun stopObserver() {
        signalObserver.stop()
    }

    fun restartObserver(next: V2SettingsSnapshot.BlindSpot = V2SettingsRepository.blindSpotConfig(context)) {
        V2AppLog.i(TAG, "refresh blind spot observer")
        updateConfig(next)
        stopObserver()
        hide()
        startObserver()
    }

    fun showPreview(side: String) {
        windowCoordinator.showPreview(side)
    }

    fun hide() {
        windowCoordinator.hide()
    }

    fun cancelAndHideForAvoidance() {
        windowCoordinator.cancelAndHideForAvoidance()
    }

    private fun handleTurnSignal(side: String, on: Boolean) {
        handler.post {
            if (!config.enabled) return@post
            windowCoordinator.handleTurnSignal(side, on)
        }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
