package com.kooo.evcam.v2.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat

internal object V2PermissionSettingsNavigator {
    private const val REQUEST_PERMISSIONS = 2101

    fun requestRuntimePermissions(context: Context, permissions: Array<String>) {
        val activity = context as? Activity
        if (activity != null) {
            ActivityCompat.requestPermissions(activity, permissions, REQUEST_PERMISSIONS)
        } else {
            openAppSettings(context)
        }
    }

    fun openManageAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        safeStart(
            context,
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") },
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") },
        )
    }

    fun openOverlaySettings(context: Context) = safeStart(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") },
    )

    fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        Toast.makeText(context, "请找到「电车记录仪 V2」相关服务并启用", Toast.LENGTH_LONG).show()
    }

    fun openUsageStatsSettings(context: Context) =
        safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun requestIgnoreBatteryOptimizations(context: Context) = safeStart(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") },
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") },
    )

    private fun openAppSettings(context: Context) = safeStart(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") },
        Intent(Settings.ACTION_SETTINGS),
    )

    private fun safeStart(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }
        Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show()
    }
}
