package com.kooo.evcam.v2.service.keepalive

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.kooo.evcam.v2.log.V2AppLog

internal object V2KeepAliveReceiverRegistry {
    private val dynamicReceivers = mutableListOf<V2KeepAliveReceiver>()
    private var dynamicRegistered = false
    private var timeTickReceiver: V2KeepAliveReceiver? = null
    private var timeTickRegistered = false

    fun registerDynamic(context: Context) {
        if (dynamicRegistered) return
        runCatching {
            registerDynamicFilter(context, V2KeepAliveIntentFilters.keepAlive(includeTimeTick = true))
            registerDynamicFilter(context, V2KeepAliveIntentFilters.media())
            registerDynamicFilter(context, V2KeepAliveIntentFilters.packageUpdates())
            dynamicRegistered = true
            V2KeepAliveStatus.setDynamicRegistered(context, true)
            V2AppLog.i(TAG, "dynamic keep alive broadcasts registered")
        }.onFailure { error -> V2AppLog.e(TAG, "dynamic keep alive broadcasts register failed", error) }
    }

    fun unregisterDynamic(context: Context) {
        if (!dynamicRegistered) return
        dynamicReceivers.forEach { receiver ->
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
                .onFailure { error -> V2AppLog.e(TAG, "dynamic keep alive broadcasts unregister failed", error) }
        }
        dynamicReceivers.clear()
        dynamicRegistered = false
        V2KeepAliveStatus.setDynamicRegistered(context, false)
        V2AppLog.i(TAG, "dynamic keep alive broadcasts unregistered")
    }

    fun registerTimeTick(context: Context) {
        if (timeTickRegistered) return
        runCatching {
            val receiver = V2KeepAliveReceiver()
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(receiver, filter)
            }
            timeTickReceiver = receiver
            timeTickRegistered = true
            V2KeepAliveStatus.setTimeTickRegistered(context, true)
            V2AppLog.i(TAG, "TIME_TICK registered")
        }.onFailure { error -> V2AppLog.e(TAG, "TIME_TICK register failed", error) }
    }

    fun unregisterTimeTick(context: Context) {
        val receiver = timeTickReceiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
            .onFailure { error -> V2AppLog.e(TAG, "TIME_TICK unregister failed", error) }
        timeTickReceiver = null
        timeTickRegistered = false
        V2KeepAliveStatus.setTimeTickRegistered(context, false)
    }

    fun sendKeepAliveCheck(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(V2KeepAliveReceiver.ACTION_KEEP_ALIVE).setPackage(context.packageName))
        }.onFailure { error -> V2AppLog.e(TAG, "send keep alive broadcast failed", error) }
    }

    private fun registerDynamicFilter(context: Context, filter: IntentFilter) {
        val receiver = V2KeepAliveReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(receiver, filter)
        }
        dynamicReceivers.add(receiver)
    }

    private const val TAG = "V2KeepAliveReceiver"
}
