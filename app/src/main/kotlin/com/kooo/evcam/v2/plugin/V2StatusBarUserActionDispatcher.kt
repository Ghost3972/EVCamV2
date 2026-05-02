package com.kooo.evcam.v2.plugin

import android.content.Context
import android.content.Intent
import android.util.Log

internal class V2StatusBarUserActionDispatcher(private val tag: String) {
    fun sendBroadcast(context: Context, intent: Intent) {
        try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(currentUserId())
            Context::class.java.getMethod("sendBroadcastAsUser", Intent::class.java, userHandleClass)
                .invoke(context, intent, userHandle)
        } catch (error: ReflectiveOperationException) {
            Log.w(tag, "sendBroadcastAsUser failed, fallback to current context user", error)
            context.sendBroadcast(intent)
        }
    }

    fun startActivity(context: Context, intent: Intent) {
        try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(currentUserId())
            Context::class.java.getMethod("startActivityAsUser", Intent::class.java, userHandleClass)
                .invoke(context, intent, userHandle)
        } catch (error: ReflectiveOperationException) {
            Log.w(tag, "startActivityAsUser failed, fallback to current context user", error)
            context.startActivity(intent)
        }
    }

    private fun currentUserId(): Int = try {
        val activityManager = Class.forName("android.app.ActivityManager")
        activityManager.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
    } catch (error: ReflectiveOperationException) {
        Log.w(tag, "getCurrentUser failed", error)
        0
    }
}
