package com.kooo.evcam.v2.service.keepalive

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

internal object V2KeepAliveStarter {
    fun requestStart(context: Context, reason: String, preferActivity: Boolean = true) {
        V2KeepAliveStatus.recordTrigger(context, "starter", reason)
        if (!V2StartupLaunchCoordinator.startupPolicy(context).autoStartOnBoot) {
            V2AppLog.i(TAG, "skip keep alive start: auto start disabled reason=$reason")
            return
        }
        V2StartupLaunchCoordinator.startForKeepAlive(context, reason, preferActivity)
    }

    private const val TAG = "V2KeepAliveStarter"
}
