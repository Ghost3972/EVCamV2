package com.kooo.evcam.v2.ui.blindspot

import android.app.Activity
import android.app.ActivityManager
import com.kooo.evcam.v2.log.V2AppLog

internal object V2BlindSpotSmallWindowTaskCleaner {
    fun removeOtherFlymeSmallWindowTasks(activity: Activity) {
        runCatching {
            val activityManager = activity.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.orEmpty().forEach { appTask ->
                val taskInfo = appTask.taskInfo ?: return@forEach
                val topClass = taskInfo.topActivity?.className.orEmpty()
                val baseClass = taskInfo.baseActivity?.className.orEmpty()
                val taskText = taskInfo.toString()
                val isFlymeSmallWindow = taskText.contains("flyme-mini-window", ignoreCase = true)
                val isBlindSpotTask = topClass == activity.javaClass.name || baseClass == activity.javaClass.name
                if (isFlymeSmallWindow && !isBlindSpotTask) {
                    appTask.finishAndRemoveTask()
                    V2AppLog.w(
                        TAG,
                        "removed non-blind-spot small window task top=$topClass base=$baseClass task=$taskText"
                    )
                }
            }
        }.onFailure {
            V2AppLog.w(TAG, "remove non-blind-spot small window task failed", it)
        }
    }

    private const val TAG = "V2BlindSpotSmallWindow"
}
