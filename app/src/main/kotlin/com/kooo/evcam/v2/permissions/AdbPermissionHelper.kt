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
            val specialGrantHandler = specialGrantHandler(callback)
            if (!cancelled && adbClient.isConnected) specialGrantHandler.handleAccessibilityService()
            if (!cancelled && adbClient.isConnected) specialGrantHandler.handleBatteryWhitelist()

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
        val success = try {
            AdbApkInstaller(
                adbClient = adbClient,
                connectAdb = { connectAdb(callback) },
                log = { message -> log(callback, message) },
            ).install(apkPath)
        } finally {
            adbClient.close()
        }
        notifyComplete(callback, success)
    }

    private fun doExecuteScript(scriptPath: String, callback: Callback) {
        val success = AdbScriptExecutor(
            adbClient = adbClient,
            connectAdb = { connectAdb(callback) },
            log = { message -> log(callback, message) },
        ).executeWithRetry(scriptPath)
        notifyComplete(callback, success)
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

    private fun specialGrantHandler(callback: Callback): AdbSpecialPermissionGrantHandler =
        AdbSpecialPermissionGrantHandler(
            packageName = packageName,
            adbClient = adbClient,
            log = { message -> log(callback, message) },
            markSuccess = { message -> markSuccess(callback, message) },
            markFailure = { message -> markFailure(callback, message) },
            isErrorResult = ::isErrorResult,
        )

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
