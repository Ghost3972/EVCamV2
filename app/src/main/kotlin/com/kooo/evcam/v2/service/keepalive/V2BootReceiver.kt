package com.kooo.evcam.v2.service.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.log.V2BroadcastLogger

class V2BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        V2AppLog.init(context)
        V2BroadcastLogger.logReceive(TAG, intent)
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON && action != HTC_QUICKBOOT_POWERON) return
        val startupPolicy = V2StartupLaunchCoordinator.startupPolicy(context)
        V2AppLog.i(
            TAG,
            "boot broadcast received: action=$action autoStart=${startupPolicy.autoStartOnBoot} " +
                "autoRecord=${startupPolicy.autoStartRecording} permissions=${V2StartupLaunchCoordinator.permissionSummary(context)}"
        )

        if (!startupPolicy.autoStartOnBoot) {
            V2AppLog.d(TAG, "skip boot start: disabled in settings")
            return
        }

        if (!V2StartupLaunchCoordinator.hasBootPermissions(context)) {
            V2AppLog.w(TAG, "skip boot start: required permissions missing ${V2StartupLaunchCoordinator.permissionSummary(context)}")
            return
        }

        V2StartupLaunchCoordinator.startForBoot(context, action)
    }

    private companion object {
        private const val TAG = "V2BootReceiver"
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
