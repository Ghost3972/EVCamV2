package com.kooo.evcam.v2.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Size
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.ui.V2BlindSpotOverlay

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

    val activeCameraIndex: Int
        get() = cameraIndex

    fun startObserver() {
        if (turnSignalObserver != null) return
        if (!V2BlindSpotSettings.isEnabled(context)) {
            V2AppLog.i(TAG, "blind spot observer skipped: disabled")
            return
        }
        val propId = V2BlindSpotSettings.turnSignalPropId(context)
        turnSignalObserver = V2VhalTurnSignalObserver(
            propId,
            V2BlindSpotSettings.LEFT_VALUE,
            V2BlindSpotSettings.RIGHT_VALUE,
            V2BlindSpotSettings.OFF_VALUE,
        ) { side, on -> handleTurnSignal(side, on) }.also { it.start() }
        V2AppLog.i(TAG, "blind spot observer started propId=$propId left=${V2BlindSpotSettings.LEFT_VALUE} right=${V2BlindSpotSettings.RIGHT_VALUE}")
    }

    fun stopObserver() {
        turnSignalObserver?.stop()
        turnSignalObserver = null
        V2AppLog.i(TAG, "blind spot observer stopped")
    }

    fun restartObserver() {
        V2AppLog.i(TAG, "refresh blind spot observer")
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
            if (!V2BlindSpotSettings.isEnabled(context)) return@post
            if (on) {
                signalIsOff = false
                cancelPendingShowHide()
                show(side)
            } else {
                signalIsOff = true
                V2AppLog.i(TAG, "blind spot signal off side=$side, hide after ${V2BlindSpotSettings.HIDE_DELAY_MS}ms")
                cancelPendingShowHide()
                handler.postDelayed({
                    if (signalIsOff) hide()
                    else V2AppLog.i(TAG, "blind spot hide canceled: signal is active again")
                }, HIDE_TOKEN, V2BlindSpotSettings.HIDE_DELAY_MS)
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
