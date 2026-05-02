package com.kooo.evcam.v2.permissions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AdbPermissionHelper(context: Context) {
    interface Callback {
        fun onLog(message: String)
        fun onComplete(allSuccess: Boolean)
    }

    private val context = context.applicationContext
    private val packageName = context.packageName
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val keyStore = AdbKeyStore(context)
    private val adbClient = AdbTcpClient(keyStore) { cancelled }

    private var successCount = 0
    private var failCount = 0
    @Volatile private var cancelled = false

    fun grantAllPermissions(callback: Callback) {
        resetState()
        executor.execute { doGrantAll(callback) }
    }

    fun cancel() {
        cancelled = true
        adbClient.close()
    }

    fun installApk(apkPath: String, callback: Callback) {
        resetState()
        executor.execute { doInstallApk(apkPath, callback) }
    }

    fun executeScriptFile(scriptPath: String, callback: Callback) {
        resetState()
        executor.execute { doExecuteScript(scriptPath, callback) }
    }

    private fun doGrantAll(callback: Callback) {
        log(callback, "=== ADB 一键获取权限 ===")
        try {
            if (!connectAdb(callback)) {
                notifyComplete(callback, false)
                return
            }

            log(callback, "✓ ADB 连接成功")
            log(callback, "")
            for (command in buildCommandList()) {
                if (cancelled || !adbClient.isConnected) {
                    log(callback, "已取消")
                    break
                }
                executePermissionCommand(command, callback)
            }
            if (!cancelled && adbClient.isConnected) handleAccessibilityService(callback)
            if (!cancelled && adbClient.isConnected) handleBatteryWhitelist(callback)

            logGrantSummary(callback)
            notifyComplete(callback, failCount == 0)
        } catch (e: SocketTimeoutException) {
            log(callback, "")
            log(callback, "✗ 连接超时")
            Log.e(TAG, "ADB timeout", e)
            notifyComplete(callback, false)
        } catch (e: Exception) {
            log(callback, "")
            log(callback, "✗ 错误: ${e.message}")
            Log.e(TAG, "ADB grant all failed", e)
            notifyComplete(callback, false)
        } finally {
            adbClient.close()
        }
    }

    private fun doInstallApk(apkPath: String, callback: Callback) {
        log(callback, "=== ADB 安装更新 ===")
        try {
            if (!connectAdb(callback)) {
                notifyComplete(callback, false)
                return
            }

            log(callback, "✓ ADB 连接成功")
            log(callback, "")
            val installPath = apkPath.toInstallPath()
            log(callback, "正在安装...")
            log(callback, "  $ pm install -r $installPath")
            log(callback, "  (安装过程可能需要 30-60 秒，请耐心等待)")

            adbClient.setReadTimeout(AdbTcpClient.INSTALL_TIMEOUT_MS)
            try {
                val result = adbClient.executeShellCommand("pm install -r $installPath").trim()
                if (result.lowercase().contains("success")) {
                    log(callback, "")
                    log(callback, "✓ 安装成功！")
                    log(callback, "  应用即将自动重启...")
                    notifyComplete(callback, true)
                } else {
                    log(callback, "")
                    log(callback, "✗ 安装失败: $result")
                    log(callback, "  请尝试手动安装")
                    notifyComplete(callback, false)
                }
            } finally {
                adbClient.setReadTimeout(READ_TIMEOUT_MS)
            }
        } catch (e: SocketTimeoutException) {
            log(callback, "")
            log(callback, "✗ 安装超时 (超过 120 秒)")
            log(callback, "  请尝试手动安装")
            Log.e(TAG, "ADB install timeout", e)
            notifyComplete(callback, false)
        } catch (e: Exception) {
            log(callback, "")
            log(callback, "✗ 错误: ${e.message}")
            Log.e(TAG, "ADB install failed", e)
            notifyComplete(callback, false)
        } finally {
            adbClient.close()
        }
    }

    private fun doExecuteScript(scriptPath: String, callback: Callback) {
        var success = attemptExecuteScript(scriptPath, callback)
        if (!success) {
            log(callback, "")
            log(callback, "========================================")
            log(callback, "[INFO] 首次执行未成功，等待 3 秒后自动重试...")
            log(callback, "========================================")
            log(callback, "")
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                notifyComplete(callback, false)
                return
            }
            success = attemptExecuteScript(scriptPath, callback)
        }
        notifyComplete(callback, success)
    }

    private fun attemptExecuteScript(scriptPath: String, callback: Callback): Boolean {
        return try {
            if (!connectAdb(callback)) return false
            log(callback, "✓ ADB 连接成功")
            log(callback, "")

            ensureRootAdbd(callback)
            remountSystem(callback)
            runScript(scriptPath, callback)
        } catch (e: Exception) {
            log(callback, "")
            log(callback, "✗ 错误: ${e.message}")
            Log.e(TAG, "Script execution failed", e)
            false
        } finally {
            adbClient.close()
        }
    }

    private fun ensureRootAdbd(callback: Callback) {
        val alreadyRoot = adbClient.executeShellCommand("id").contains("uid=0")
        if (alreadyRoot) {
            log(callback, "[INFO] adbd 已是 root，跳过 adb root")
            return
        }

        log(callback, "[INFO] 执行 adb root ...")
        adbClient.close()
        adbClient.connectLocal()
        adbClient.performHandshake { log(callback, it) }
        log(callback, "[INFO]   ${adbClient.executeService("root:")}")
        adbClient.close()
        log(callback, "[INFO] 等待 adbd 以 root 身份重启...")
        adbClient.waitForAdbd(8000)
    }

    private fun remountSystem(callback: Callback) {
        adbClient.connectLocal()
        if (adbClient.performHandshake { log(callback, it) }) {
            log(callback, "[INFO] 执行 adb remount ...")
            log(callback, "[INFO]   ${adbClient.executeService("remount:")}")
        } else {
            log(callback, "[WARN] remount 握手失败，继续执行脚本")
        }
        adbClient.close()
        log(callback, "")
    }

    private fun runScript(scriptPath: String, callback: Callback): Boolean {
        adbClient.connectLocal(readTimeoutMs = SCRIPT_TIMEOUT_MS)
        if (!adbClient.performHandshake { log(callback, it) }) {
            log(callback, "✗ ADB 连接握手失败")
            return false
        }
        val success = adbClient.executeShellCommandStreaming("sh $scriptPath") { log(callback, it) }
        log(callback, "")
        log(callback, if (success) "✓ 脚本执行成功" else "✗ 脚本执行过程中出现错误，请检查日志")
        return success
    }

    private fun connectAdb(callback: Callback): Boolean {
        adbClient.resetSession()
        adbClient.resetConnection { log(callback, it) }
        keyStore.loadOrGenerate()
        if (!adbClient.connectWithCandidates { log(callback, it) }) return false
        if (adbClient.performHandshake { log(callback, it) }) return true
        log(callback, "\n✗ ADB 连接握手失败")
        return false
    }

    private fun executePermissionCommand(command: AdbPermissionCommand, callback: Callback) {
        log(callback, "[${command.description}]")
        log(callback, "  $ ${command.shellCommand}")
        try {
            val result = adbClient.executeShellCommand(command.shellCommand).trim()
            when {
                result.isEmpty() -> markSuccess(callback, "  ✓ 成功")
                isErrorResult(result) -> markFailure(callback, "  ✗ $result")
                else -> markSuccess(callback, "  → $result")
            }
        } catch (e: Exception) {
            markFailure(callback, "  ✗ ${e.message}")
        }
    }

    private fun handleAccessibilityService(callback: Callback) {
        val serviceName = "$packageName/$packageName.service.V2KeepAliveAccessibilityService"
        log(callback, "[无障碍服务]")
        try {
            log(callback, "  $ settings get secure enabled_accessibility_services")
            val current = adbClient.executeShellCommand("settings get secure enabled_accessibility_services").trim()
            log(callback, "  → 当前: ${if (current.isEmpty() || current == "null") "(无)" else current}")
            if (current.contains(packageName)) {
                markSuccess(callback, "  ✓ 已启用")
                return
            }

            val newValue = if (current.isEmpty() || current == "null") serviceName else "$current:$serviceName"
            val putCommand = "settings put secure enabled_accessibility_services $newValue"
            log(callback, "  $ $putCommand")
            val result = adbClient.executeShellCommand(putCommand).trim()
            if (result.isNotEmpty() && isErrorResult(result)) {
                markFailure(callback, "  ✗ $result")
                return
            }

            val enableCommand = "settings put secure accessibility_enabled 1"
            log(callback, "  $ $enableCommand")
            adbClient.executeShellCommand(enableCommand)
            markSuccess(callback, "  ✓ 成功")
        } catch (e: Exception) {
            markFailure(callback, "  ✗ ${e.message}")
        }
    }

    private fun handleBatteryWhitelist(callback: Callback) {
        log(callback, "[电池优化白名单]")
        val command = "dumpsys deviceidle whitelist +$packageName"
        log(callback, "  $ $command")
        try {
            val result = adbClient.executeShellCommand(command).trim()
            when {
                result.isEmpty() -> markSuccess(callback, "  ✓ 成功")
                result.lowercase().contains("added") || result.lowercase().contains("already") -> {
                    markSuccess(callback, "  ✓ $result")
                }
                isErrorResult(result) -> markFailure(callback, "  ✗ $result")
                else -> markSuccess(callback, "  → $result")
            }
        } catch (e: Exception) {
            markFailure(callback, "  ✗ ${e.message}")
        }
    }

    private fun buildCommandList(): List<AdbPermissionCommand> =
        AdbPermissionGrantPlan.build(packageName, android.os.Build.VERSION.SDK_INT)

    private fun resetState() {
        cancelled = false
        successCount = 0
        failCount = 0
    }

    private fun logGrantSummary(callback: Callback) {
        log(callback, "")
        log(callback, "=== 执行完成 ===")
        log(callback, "成功: $successCount  失败: $failCount")
        log(callback, if (failCount == 0) "所有权限已授予，请返回查看状态" else "部分权限授予失败，请检查上方日志")
    }

    private fun markSuccess(callback: Callback, message: String) {
        log(callback, message)
        successCount++
    }

    private fun markFailure(callback: Callback, message: String) {
        log(callback, message)
        failCount++
    }

    private fun String.toInstallPath(): String {
        return if (startsWith(EMULATED_STORAGE_PREFIX)) {
            DATA_MEDIA_PREFIX + substring(EMULATED_STORAGE_PREFIX.length)
        } else {
            this
        }
    }

    private fun isErrorResult(result: String): Boolean {
        val lower = result.lowercase()
        return ERROR_MARKERS.any { lower.contains(it) }
    }

    private fun log(callback: Callback, message: String) {
        mainHandler.post { callback.onLog(message) }
    }

    private fun notifyComplete(callback: Callback, allSuccess: Boolean) {
        mainHandler.post { callback.onComplete(allSuccess) }
    }

    private companion object {
        const val TAG = "AdbPermissionHelper"
        const val READ_TIMEOUT_MS = 10000
        const val SCRIPT_TIMEOUT_MS = 60000
        const val EMULATED_STORAGE_PREFIX = "/storage/emulated/"
        const val DATA_MEDIA_PREFIX = "/data/media/"
        val ERROR_MARKERS = listOf(
            "exception",
            "error",
            "unknown permission",
            "not found",
            "failure",
            "security",
            "not allowed",
        )
    }
}
