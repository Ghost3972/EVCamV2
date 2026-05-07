package com.kooo.evcam.v2.ui.main

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService

internal class V2MainSmallWindowGuard(
    private val activity: AppCompatActivity,
    private val service: () -> V2CameraForegroundService?,
) {
    fun closeIfLaunchedInSmallWindow(reason: String): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return true
        val multiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode
        val flymeSmallWindow = isCurrentConfigurationInFlymeSmallWindow() ||
            isCurrentTaskInFlymeSmallWindow() ||
            isCurrentWindowPortraitLike()
        if (!multiWindow && !flymeSmallWindow) return false
        V2AppLog.w(
            TAG,
            "main preview launched in small window, closing task reason=$reason " +
                "multiWindow=$multiWindow flymeSmallWindow=$flymeSmallWindow taskId=${activity.taskId}"
        )
        service()?.setUiVisibility(false)
        finishCurrentTaskFromSmallWindow()
        return true
    }

    fun restoreMainWindowMode() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun isCurrentConfigurationInFlymeSmallWindow(): Boolean = runCatching {
        val windowConfiguration = activity.resources.configuration.javaClass.methods
            .firstOrNull { it.name == "getWindowConfiguration" && it.parameterTypes.isEmpty() }
            ?.invoke(activity.resources.configuration)
            ?: return@runCatching false
        val description = windowConfiguration.toString()
        val mode = runCatching {
            windowConfiguration.javaClass.getMethod("getWindowingMode").invoke(windowConfiguration) as? Int
        }.getOrNull()
        description.contains("flyme-mini-window", ignoreCase = true) || mode == FLYME_MINI_WINDOW_MODE
    }.onFailure {
        V2AppLog.d(TAG, "small window configuration check unavailable: ${it.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun isCurrentTaskInFlymeSmallWindow(): Boolean = runCatching {
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        activityManager.getRunningTasks(20).orEmpty().any { task ->
            task.taskId == activity.taskId && task.toString().contains("flyme-mini-window", ignoreCase = true)
        }
    }.onFailure {
        V2AppLog.w(TAG, "small window task check failed", it)
    }.getOrDefault(false)

    private fun isCurrentWindowPortraitLike(): Boolean {
        val decorWidth = activity.window.decorView.width
        val decorHeight = activity.window.decorView.height
        if (decorWidth > 0 && decorHeight > decorWidth) return true
        val metrics = activity.resources.displayMetrics
        return metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.heightPixels > metrics.widthPixels
    }

    private fun finishCurrentTaskFromSmallWindow() {
        runCatching {
            val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.firstOrNull { appTask ->
                runCatching { appTask.taskInfo?.taskId == activity.taskId }.getOrDefault(false)
            }?.let { appTask ->
                V2AppLog.w(TAG, "remove current small-window task taskId=${activity.taskId}")
                appTask.finishAndRemoveTask()
                return
            }
        }.onFailure {
            V2AppLog.w(TAG, "remove current small-window task failed", it)
        }
        activity.finishAffinity()
        activity.finishAndRemoveTask()
    }

    private companion object {
        private const val TAG = "V2MainActivity"
        private const val FLYME_MINI_WINDOW_MODE = 11
    }
}
