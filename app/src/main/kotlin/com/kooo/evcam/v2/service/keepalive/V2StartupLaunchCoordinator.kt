package com.kooo.evcam.v2.service.keepalive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot
import com.kooo.evcam.v2.ui.main.V2TransparentBootActivity

internal object V2StartupLaunchCoordinator {
    fun startupPolicy(context: Context): V2SettingsSnapshot.Startup =
        V2SettingsRepository.startupPolicy(context)

    fun hasBootPermissions(context: Context): Boolean = isGranted(context, Manifest.permission.CAMERA)

    fun permissionSummary(context: Context): String {
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        return "camera=${isGranted(context, Manifest.permission.CAMERA)} notification=$notificationGranted"
    }

    fun startForKeepAlive(context: Context, reason: String, preferActivity: Boolean) {
        if (preferActivity && Settings.canDrawOverlays(context)) {
            startTransparentActivity(
                context = context,
                reason = reason,
                extraKey = EXTRA_KEEP_ALIVE_REASON,
                successLog = "transparent activity start requested reason=$reason",
                failureLog = "transparent activity start failed, fallback service reason=$reason",
            )
        } else {
            startForegroundService(context, "foreground service start requested reason=$reason", reason)
        }
    }

    fun startForBoot(context: Context, action: String) {
        startTransparentActivity(
            context = context,
            reason = action,
            extraKey = EXTRA_BOOT_ACTION,
            successLog = "transparent boot activity start requested action=$action",
            failureLog = "start transparent boot activity failed, fallback to foreground service",
            fallbackSuccessLog = "boot fallback foreground service start requested",
            fallbackFailureLog = "start boot fallback foreground service failed",
        )
    }

    private fun startTransparentActivity(
        context: Context,
        reason: String,
        extraKey: String,
        successLog: String,
        failureLog: String,
        fallbackSuccessLog: String = "foreground service start requested reason=$reason",
        fallbackFailureLog: String = "foreground service start failed reason=$reason",
    ) {
        runCatching {
            val intent = Intent(context, V2TransparentBootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(extraKey, reason)
            }
            context.startActivity(intent)
            V2AppLog.i(TAG, successLog)
        }.onFailure { error ->
            V2AppLog.e(TAG, failureLog, error)
            startForegroundService(context, fallbackSuccessLog, reason, fallbackFailureLog)
        }
    }

    private fun startForegroundService(
        context: Context,
        successLog: String,
        reason: String,
        failureLog: String = "foreground service start failed reason=$reason",
    ) {
        runCatching {
            V2CameraServiceCommands.start(context)
            V2AppLog.i(TAG, successLog)
        }.onFailure { error ->
            V2AppLog.e(TAG, failureLog, error)
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "V2StartupLaunch"
    private const val EXTRA_BOOT_ACTION = "boot_action"
    private const val EXTRA_KEEP_ALIVE_REASON = "keep_alive_reason"
}
