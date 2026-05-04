package com.kooo.evcam.v2.service.status

import android.content.Intent
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.ui.main.V2MainActivity

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
        val intent = Intent(service, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { service.startActivity(intent) }
            .onFailure { V2AppLog.e(TAG, "$reason UI failed", it) }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
