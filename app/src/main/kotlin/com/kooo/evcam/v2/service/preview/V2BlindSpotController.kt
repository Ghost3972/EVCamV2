package com.kooo.evcam.v2.service.preview

import android.content.Context
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.vhal.V2VhalTurnSignalObserver
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot
import com.kooo.evcam.v2.ui.blindspot.V2BlindSpotOverlay

class V2BlindSpotController(
    private val context: Context,
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val shouldAvoidWindow: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val previewIndexForSide: (String) -> Int?,
    private val previewDescription: (Int) -> String,
    private val previewInputSize: (Int) -> Size?,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
    private val restoreMainPreview: (Int) -> Unit,
    private val hideFisheyePreview: () -> Unit,
    private val hideUi: () -> Unit,
    private val restoreUi: () -> Unit,
    private val renderedFrames: (Int) -> Long,
    private val showToast: (String) -> Unit,
) {
    private var turnSignalObserver: V2VhalTurnSignalObserver? = null
    private var overlay: V2BlindSpotOverlay? = null
    private var cameraIndex = -1
    private var restoreUiAfterOverlay = false
    @Volatile private var signalIsOff = true
    @Volatile private var config: V2SettingsSnapshot.BlindSpot = V2SettingsRepository.blindSpotConfig(context)

    val activeCameraIndex: Int
        get() = cameraIndex

    fun startObserver() {
        if (turnSignalObserver != null) return
        val current = config
        if (!current.enabled) {
            V2AppLog.i(TAG, "blind spot observer skipped: disabled")
            return
        }
        turnSignalObserver = V2VhalTurnSignalObserver(
            current.turnSignalPropId,
            current.leftValue,
            current.rightValue,
            current.offValue,
        ) { side, on -> handleTurnSignal(side, on) }.also { it.start() }
        V2AppLog.i(TAG, "blind spot observer started propId=${current.turnSignalPropId} left=${current.leftValue} right=${current.rightValue}")
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
        turnSignalObserver?.stop()
        turnSignalObserver = null
        V2AppLog.i(TAG, "blind spot observer stopped")
    }

    fun restartObserver(next: V2SettingsSnapshot.BlindSpot = V2SettingsRepository.blindSpotConfig(context)) {
        V2AppLog.i(TAG, "refresh blind spot observer")
        updateConfig(next)
        stopObserver()
        hide()
        startObserver()
    }

    fun showPreview(side: String) {
        val normalizedSide = if (side == "right") "right" else "left"
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "blind spot preview skipped: display off side=$normalizedSide")
            return
        }
        if (!hasOverlayPermission()) {
            V2AppLog.w(TAG, "blind spot preview skipped: overlay permission missing")
            showToast("补盲预览需要悬浮窗权限")
            return
        }
        cancelPendingShowHide()
        V2AppLog.i(TAG, "blind spot correction preview side=$normalizedSide")
        showNow(normalizedSide)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(SHOW_TOKEN)
        val index = cameraIndex
        overlay?.hide()
        cameraIndex = -1
        if (index >= 0 && isDisplayPowerOn()) restoreMainPreview(index)
        restoreUiAfterOverlayIfNeeded()
        V2AppLog.i(TAG, "blind spot overlay hidden index=$index")
    }

    fun cancelAndHideForAvoidance() {
        cancelPendingShowHide()
        restoreUiAfterOverlay = false
        hide()
    }

    private fun handleTurnSignal(side: String, on: Boolean) {
        handler.post {
            if (!config.enabled) return@post
            if (on) {
                signalIsOff = false
                cancelPendingShowHide()
                show(side)
            } else {
                signalIsOff = true
                val hideDelayMs = config.hideDelayMs
                V2AppLog.i(TAG, "blind spot signal off side=$side, hide after ${hideDelayMs}ms")
                cancelPendingShowHide()
                handler.postDelayed({
                    if (signalIsOff) hide()
                    else V2AppLog.i(TAG, "blind spot hide canceled: signal is active again")
                }, HIDE_TOKEN, hideDelayMs)
            }
        }
    }

    private fun show(side: String) {
        if (shouldAvoidWindow()) {
            V2AppLog.i(TAG, "blind spot show skipped: blind spot avoidance active target=${avoidanceTarget()} side=$side")
            handler.removeCallbacksAndMessages(SHOW_TOKEN)
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "blind spot show skipped: display off side=$side")
            return
        }
        if (!hasOverlayPermission()) {
            V2AppLog.w(TAG, "blind spot show skipped: overlay permission missing")
            showToast("补盲悬浮窗需要悬浮窗权限")
            return
        }
        if (isUiVisible()) {
            V2AppLog.i(TAG, "blind spot hide preview UI before overlay side=$side")
            restoreUiAfterOverlay = true
            hideUi()
            handler.removeCallbacksAndMessages(SHOW_TOKEN)
            handler.postDelayed({
                val avoid = shouldAvoidWindow()
                if (!signalIsOff && !avoid) showNow(side)
                else if (avoid) {
                    restoreUiAfterOverlay = false
                    V2AppLog.i(TAG, "blind spot delayed show canceled: blind spot avoidance active target=${avoidanceTarget()}")
                } else {
                    V2AppLog.i(TAG, "blind spot delayed show canceled: signal is off")
                }
            }, SHOW_TOKEN, SHOW_AFTER_UI_HIDE_MS)
            return
        }
        showNow(side)
    }

    private fun showNow(side: String) {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        if (shouldAvoidWindow()) {
            V2AppLog.i(TAG, "blind spot showNow skipped: blind spot avoidance active target=${avoidanceTarget()} side=$side")
            return
        }
        val index = previewIndexForSide(side) ?: run {
            V2AppLog.w(TAG, "blind spot show skipped: no preview index for side=$side")
            return
        }
        val previousIndex = cameraIndex
        hideFisheyePreview()
        cameraIndex = index
        if (overlay == null) {
            overlay = V2BlindSpotOverlay(
                context = context,
                attachPreview = attachPreview,
                detachPreview = detachPreview,
                previewInputSize = previewInputSize,
                renderedFrames = renderedFrames,
                onClose = { hide() },
            )
        }
        V2AppLog.i(TAG, "blind spot show side=$side ${previewDescription(index)}")
        overlay?.show(side, index)
        if (previousIndex >= 0 && previousIndex != index && isDisplayPowerOn()) restoreMainPreview(previousIndex)
        V2AppLog.perf("V2BlindSpotPerf", "show", android.os.SystemClock.elapsedRealtime() - startedMs, "side=$side index=$index previous=$previousIndex")
    }

    private fun restoreUiAfterOverlayIfNeeded() {
        if (!restoreUiAfterOverlay) return
        restoreUiAfterOverlay = false
        if (!isDisplayPowerOn() || isUiVisible()) return
        V2AppLog.i(TAG, "blind spot restore preview UI")
        restoreUi()
    }

    private fun cancelPendingShowHide() {
        handler.removeCallbacksAndMessages(HIDE_TOKEN)
        handler.removeCallbacksAndMessages(SHOW_TOKEN)
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private companion object {
        private const val TAG = "V2CameraService"
        private const val HIDE_TOKEN = "blind_spot_hide"
        private const val SHOW_TOKEN = "blind_spot_show"
        private const val SHOW_AFTER_UI_HIDE_MS = 300L
    }
}
