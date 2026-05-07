package com.kooo.evcam.v2.service.status

import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.ui.main.V2MainActivityLauncher

internal class V2UiVisibilityOrchestrator(
    private val service: V2CameraForegroundService,
    private val onVisibilityChanged: () -> Unit,
) {
    var isVisible: Boolean = false
        private set

    private var hideListener: (() -> Unit)? = null

    fun setVisible(visible: Boolean, listener: (() -> Unit)? = null) {
        isVisible = visible
        hideListener = if (visible) listener else null
        V2AppLog.d(TAG, "uiVisible=$isVisible")
        onVisibilityChanged()
    }

    fun showFromCustomKey() {
        showUi("custom key")
    }

    fun hideForAvoidance() {
        if (isVisible && hideListener != null) {
            hideListener?.invoke()
            return
        }
        V2AppLog.w(TAG, "avoidance hide UI skipped: ui callback unavailable")
    }

    fun restoreFromAvoidance() {
        showUi("avoidance restore")
    }

    fun restoreFromBlindSpot() {
        showUi("blind spot restore")
    }

    private fun showUi(reason: String) {
        runCatching { V2MainActivityLauncher.start(service, TAG) }
            .onFailure { V2AppLog.e(TAG, "$reason UI failed", it) }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
