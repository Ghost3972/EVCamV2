package com.kooo.evcam.v2.service.preview

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.ui.blindspot.V2BlindSpotSmallWindowActivity

internal class V2BlindSpotWindowCoordinator(
    private val context: Context,
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val shouldAvoidWindow: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val previewIndexForSide: (String) -> Int?,
    private val previewDescription: (Int) -> String,
    private val restoreMainPreview: (Int) -> Unit,
    private val hideFisheyePreview: () -> Unit,
    private val hideUi: () -> Unit,
) {
    private var cameraIndex = -1
    private var activeSide: String? = null
    @Volatile private var signalIsOff = true

    val activeCameraIndex: Int
        get() = cameraIndex

    fun showPreview(side: String) {
        val normalizedSide = if (side == "right") "right" else "left"
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "blind spot preview skipped: display off side=$normalizedSide")
            return
        }
        cancelPendingShowHide()
        V2AppLog.i(TAG, "blind spot correction preview side=$normalizedSide")
        showNow(normalizedSide, forceRefresh = true)
    }

    fun handleTurnSignal(side: String, on: Boolean) {
        if (on) {
            signalIsOff = false
            cancelPendingShowHide()
            show(side)
            return
        }

        signalIsOff = true
        cancelPendingShowHide()
        handler.postDelayed({
            if (signalIsOff && activeSide == side) {
                hide()
                V2AppLog.i(TAG, "blind spot signal off side=$side, hide after debounce")
            } else {
                V2AppLog.i(TAG, "blind spot hide canceled: signal active again side=$side active=$activeSide")
            }
        }, HIDE_TOKEN, OFF_HIDE_DEBOUNCE_MS)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(SHOW_TOKEN)
        val index = cameraIndex
        V2BlindSpotSmallWindowActivity.finishActiveFromService()
        cameraIndex = -1
        activeSide = null
        if (index >= 0 && isDisplayPowerOn()) restoreMainPreview(index)
        V2AppLog.i(TAG, "blind spot small window hidden index=$index")
    }

    fun cancelAndHideForAvoidance() {
        cancelPendingShowHide()
        hide()
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
        if (isUiVisible()) {
            V2AppLog.i(TAG, "blind spot hide preview UI before small window side=$side")
            hideUi()
            handler.removeCallbacksAndMessages(SHOW_TOKEN)
            handler.postDelayed({
                val avoid = shouldAvoidWindow()
                if (!signalIsOff && !avoid) {
                    showNow(side)
                } else if (avoid) {
                    V2AppLog.i(TAG, "blind spot delayed show canceled: blind spot avoidance active target=${avoidanceTarget()}")
                } else {
                    V2AppLog.i(TAG, "blind spot delayed show canceled: signal is off")
                }
            }, SHOW_TOKEN, SHOW_AFTER_UI_HIDE_MS)
            return
        }
        showNow(side)
    }

    private fun showNow(side: String, forceRefresh: Boolean = false) {
        val startedMs = SystemClock.elapsedRealtime()
        if (shouldAvoidWindow()) {
            V2AppLog.i(TAG, "blind spot showNow skipped: blind spot avoidance active target=${avoidanceTarget()} side=$side")
            return
        }
        val index = previewIndexForSide(side) ?: run {
            V2AppLog.w(TAG, "blind spot show skipped: no preview index for side=$side")
            return
        }
        val previousIndex = cameraIndex
        val previousSide = activeSide
        if (previousIndex == index && previousSide == side) {
            if (forceRefresh) {
                V2BlindSpotSmallWindowActivity.show(context, side, index)
                V2AppLog.i(TAG, "blind spot small window refreshed side=$side index=$index")
            } else {
                V2AppLog.i(TAG, "blind spot show skipped: already active side=$side index=$index")
            }
            return
        }
        hideFisheyePreview()
        cameraIndex = index
        activeSide = side
        V2AppLog.i(TAG, "blind spot show small window side=$side ${previewDescription(index)}")
        if (previousIndex >= 0 && (previousIndex != index || previousSide != side)) {
            V2BlindSpotSmallWindowActivity.finishActiveFromService()
            handler.postDelayed({
                if (signalIsOff) {
                    V2AppLog.i(TAG, "blind spot recreate canceled: signal is off side=$side index=$index")
                    return@postDelayed
                }
                if (shouldAvoidWindow()) {
                    V2AppLog.i(TAG, "blind spot recreate canceled: blind spot avoidance active target=${avoidanceTarget()} side=$side")
                    return@postDelayed
                }
                if (cameraIndex != index || activeSide != side) {
                    V2AppLog.i(TAG, "blind spot recreate canceled: active target changed expected=$side/$index actual=$activeSide/$cameraIndex")
                    return@postDelayed
                }
                V2BlindSpotSmallWindowActivity.show(context, side, index)
                if (isDisplayPowerOn()) restoreMainPreview(previousIndex)
                V2AppLog.i(TAG, "blind spot small window recreated side=$side ${previewDescription(index)}")
            }, SHOW_TOKEN, RECREATE_SMALL_WINDOW_DELAY_MS)
        } else {
            V2BlindSpotSmallWindowActivity.show(context, side, index)
        }
        V2AppLog.perf("V2BlindSpotPerf", "show", SystemClock.elapsedRealtime() - startedMs, "side=$side index=$index previous=$previousIndex")
    }

    private fun cancelPendingShowHide() {
        handler.removeCallbacksAndMessages(HIDE_TOKEN)
        handler.removeCallbacksAndMessages(SHOW_TOKEN)
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val HIDE_TOKEN = "blind_spot_hide"
        private const val SHOW_TOKEN = "blind_spot_show"
        private const val SHOW_AFTER_UI_HIDE_MS = 300L
        private const val RECREATE_SMALL_WINDOW_DELAY_MS = 0L
        private const val OFF_HIDE_DEBOUNCE_MS = 500L
    }
}
