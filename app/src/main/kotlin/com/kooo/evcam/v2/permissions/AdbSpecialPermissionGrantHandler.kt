package com.kooo.evcam.v2.permissions

import com.kooo.evcam.v2.service.keepalive.V2KeepAliveAccessibilityService

internal class AdbSpecialPermissionGrantHandler(
    private val packageName: String,
    private val adbClient: AdbTcpClient,
    private val log: (String) -> Unit,
    private val markSuccess: (String) -> Unit,
    private val markFailure: (String) -> Unit,
    private val isErrorResult: (String) -> Boolean,
) {
    fun handleAccessibilityService() {
        val serviceName = "$packageName/${V2KeepAliveAccessibilityService::class.java.name}"
        log("[无障碍服务]")
        try {
            log("  $ settings get secure enabled_accessibility_services")
            val current = adbClient.executeShellCommand("settings get secure enabled_accessibility_services").trim()
            log("  → 当前: ${if (current.isEmpty() || current == "null") "(无)" else current}")
            if (current.contains(packageName)) {
                markSuccess("  ✓ 已启用")
                return
            }

            val newValue = if (current.isEmpty() || current == "null") serviceName else "$current:$serviceName"
            val putCommand = "settings put secure enabled_accessibility_services $newValue"
            log("  $ $putCommand")
            val result = adbClient.executeShellCommand(putCommand).trim()
            if (result.isNotEmpty() && isErrorResult(result)) {
                markFailure("  ✗ $result")
                return
            }

            val enableCommand = "settings put secure accessibility_enabled 1"
            log("  $ $enableCommand")
            adbClient.executeShellCommand(enableCommand)
            markSuccess("  ✓ 成功")
        } catch (error: Exception) {
            markFailure("  ✗ ${error.message}")
        }
    }

    fun handleBatteryWhitelist() {
        log("[电池优化白名单]")
        val command = "dumpsys deviceidle whitelist +$packageName"
        log("  $ $command")
        try {
            val result = adbClient.executeShellCommand(command).trim()
            when {
                result.isEmpty() -> markSuccess("  ✓ 成功")
                result.lowercase().contains("added") || result.lowercase().contains("already") -> {
                    markSuccess("  ✓ $result")
                }
                isErrorResult(result) -> markFailure("  ✗ $result")
                else -> markSuccess("  → $result")
            }
        } catch (error: Exception) {
            markFailure("  ✗ ${error.message}")
        }
    }
}
