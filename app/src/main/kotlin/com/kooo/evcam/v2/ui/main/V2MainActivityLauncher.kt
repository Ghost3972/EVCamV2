package com.kooo.evcam.v2.ui.main

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

internal object V2MainActivityLauncher {
    fun start(context: Context, logTag: String? = null) {
        val intent = mainIntent(context)
        val options = launchOptions()
        if (startAsCurrentUser(context, intent, options, logTag)) return
        context.startActivity(intent, options)
    }

    private fun mainIntent(context: Context): Intent =
        Intent(context, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_WINDOW_MODE, WINDOW_MODE_FULL)
        }

    private fun launchOptions() = ActivityOptions.makeBasic().toBundle().apply {
        putInt(START_WINDOW_MODE_KEY, START_WINDOW_MODE_FULL)
    }

    private fun startAsCurrentUser(context: Context, intent: Intent, options: Bundle, logTag: String?): Boolean {
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val currentUserId = currentUserId(logTag)
            val userHandle = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(currentUserId)
            Context::class.java.getMethod("startActivityAsUser", Intent::class.java, Bundle::class.java, userHandleClass)
                .invoke(context, intent, options, userHandle)
            true
        } catch (error: Throwable) {
            logTag?.let { Log.w(it, "startActivityAsUser failed, fallback to normal startActivity", error) }
            false
        }
    }

    private fun currentUserId(logTag: String?): Int = try {
        val activityManager = Class.forName("android.app.ActivityManager")
        activityManager.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
    } catch (error: Throwable) {
        logTag?.let { Log.w(it, "getCurrentUser failed, fallback user 0", error) }
        0
    }

    private const val EXTRA_WINDOW_MODE = "windowMode"
    private const val START_WINDOW_MODE_KEY = "start_windowmode"
    private const val START_WINDOW_MODE_FULL = -2
    private const val WINDOW_MODE_FULL = 1
}
