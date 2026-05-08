package com.kooo.evcam.v2.service.settings

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsCategory
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2SettingsRuntimeCoordinator(
    private val handler: Handler,
    private val loadSnapshot: () -> V2SettingsSnapshot,
    private val loadFisheye: () -> V2SettingsSnapshot.Fisheye,
    private val loadAvoidance: () -> V2SettingsSnapshot.Avoidance,
    private val loadBlindSpot: () -> V2SettingsSnapshot.BlindSpot,
    private val loadCustomKey: () -> V2SettingsSnapshot.CustomKey,
    private val applyFisheye: (V2SettingsSnapshot.Fisheye) -> Unit,
    private val restartBlindSpot: (V2SettingsSnapshot.BlindSpot) -> Unit,
    private val restartCustomKey: (V2SettingsSnapshot.CustomKey) -> Unit,
    private val refreshWakeLock: () -> Unit,
    private val updateAvoidance: (V2SettingsSnapshot.Avoidance) -> Unit,
) {
    private var pendingFisheye: Runnable? = null

    fun onSettingsChanged(category: String?) {
        val normalized = category?.ifBlank { V2SettingsCategory.ALL } ?: V2SettingsCategory.ALL
        when (normalized) {
            V2SettingsCategory.FISHEYE -> {
                pendingFisheye?.let { handler.removeCallbacks(it) }
                pendingFisheye = null
                applyFisheye(loadFisheye())
            }
            V2SettingsCategory.BLIND_SPOT -> restartBlindSpot(loadBlindSpot())
            V2SettingsCategory.CUSTOM_KEY -> restartCustomKey(loadCustomKey())
            V2SettingsCategory.AVOIDANCE -> updateAvoidance(loadAvoidance())
            V2SettingsCategory.WAKE_LOCK -> refreshWakeLock()
            V2SettingsCategory.ALL -> {
                pendingFisheye?.let { handler.removeCallbacks(it) }
                val snapshot = loadSnapshot()
                updateAvoidance(snapshot.avoidance)
                applyFisheye(snapshot.fisheye)
                restartBlindSpot(snapshot.blindSpot)
                restartCustomKey(snapshot.customKey)
                refreshWakeLock()
            }
            else -> V2AppLog.w(TAG, "settings changed ignored: unknown category=$normalized")
        }
        V2AppLog.i(TAG, "settings changed category=$normalized")
    }

    private companion object {
        private const val TAG = "V2SettingsRuntime"
    }
}
