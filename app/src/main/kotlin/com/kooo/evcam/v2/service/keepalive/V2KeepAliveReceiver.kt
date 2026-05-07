package com.kooo.evcam.v2.service.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class V2KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        V2KeepAliveBroadcastHandler.handle(context, intent)
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "com.kooo.evcam.v2.action.KEEP_ALIVE"

        fun registerDynamic(context: Context) = V2KeepAliveReceiverRegistry.registerDynamic(context)

        fun unregisterDynamic(context: Context) = V2KeepAliveReceiverRegistry.unregisterDynamic(context)

        fun registerTimeTick(context: Context) = V2KeepAliveReceiverRegistry.registerTimeTick(context)

        fun unregisterTimeTick(context: Context) = V2KeepAliveReceiverRegistry.unregisterTimeTick(context)

        fun sendKeepAliveCheck(context: Context) = V2KeepAliveReceiverRegistry.sendKeepAliveCheck(context)
    }
}
