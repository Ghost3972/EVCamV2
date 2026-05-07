package com.kooo.evcam.v2.service.keepalive

import android.content.Context
import android.content.Intent
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.service.display.V2DisplayPowerActions
import com.kooo.evcam.v2.service.display.V2DisplayPowerState
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import com.kooo.evcam.v2.storage.V2StoragePathHelper

internal object V2KeepAliveBroadcastHandler {
    private var lastTriggerMs = 0L

    fun handle(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        V2AppLog.init(context)
        V2BroadcastLogger.logReceive(TAG, intent)
        updateDisplayPowerState(action)
        refreshStorageCacheIfNeeded(context, action)

        val reason = V2KeepAliveActionClassifier.reasonFor(action)
        V2KeepAliveStatus.recordTrigger(context, "broadcast", reason)
        if (!V2SettingsRepository.keepAlivePolicy(context).enabled) {
            V2AppLog.i(TAG, "skip broadcast: keep alive disabled action=$action")
            return
        }
        if (action == Intent.ACTION_TIME_TICK) {
            V2AppLog.d(TAG, "time tick keep alive")
            ensureServicesRunning(context, "time_tick", quiet = true, preferActivity = false)
            return
        }
        ensureServicesRunning(
            context = context,
            reason = reason,
            quiet = V2KeepAliveActionClassifier.isQuietAction(action),
            preferActivity = V2KeepAliveActionClassifier.shouldStartThroughActivity(action),
        )
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            V2KeepAliveReceiverRegistry.registerTimeTick(context)
        }
    }

    private fun updateDisplayPowerState(action: String) {
        if (!V2DisplayPowerActions.isDisplayOff(action)) return
        val displayPowerOn = V2DisplayPowerState.updateFromAction(action)
        V2AppLog.i(TAG, "display power state updated action=$action on=$displayPowerOn")
    }

    private fun refreshStorageCacheIfNeeded(context: Context, action: String) {
        if (!V2KeepAliveActionClassifier.isStorageChangeAction(action)) return
        V2StoragePathHelper.clearCache()
        V2PlaybackCacheMaintainer.scheduleRefresh(context)
        V2AppLog.i(TAG, "storage change received; refreshed USB detection cache action=$action")
    }

    private fun ensureServicesRunning(
        context: Context,
        reason: String,
        quiet: Boolean = false,
        preferActivity: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < MIN_TRIGGER_INTERVAL_MS) return
        lastTriggerMs = now
        if (!quiet) V2AppLog.i(TAG, "keep alive trigger reason=$reason preferActivity=$preferActivity")
        V2KeepAliveStarter.requestStart(context.applicationContext, reason, preferActivity = preferActivity)
    }

    private const val TAG = "V2KeepAliveReceiver"
    private const val MIN_TRIGGER_INTERVAL_MS = 3_000L
}
