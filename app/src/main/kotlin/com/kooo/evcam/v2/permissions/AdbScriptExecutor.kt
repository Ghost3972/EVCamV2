package com.kooo.evcam.v2.permissions

import android.util.Log

internal class AdbScriptExecutor(
    private val adbClient: AdbTcpClient,
    private val connectAdb: () -> Boolean,
    private val log: (String) -> Unit,
) {
    fun executeWithRetry(scriptPath: String): Boolean {
        var success = attemptExecuteScript(scriptPath)
        if (!success) {
            log("")
            log("========================================")
            log("[INFO] 首次执行未成功，等待 3 秒后自动重试...")
            log("========================================")
            log("")
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            success = attemptExecuteScript(scriptPath)
        }
        return success
    }

    private fun attemptExecuteScript(scriptPath: String): Boolean {
        return try {
            if (!connectAdb()) return false
            log("✓ ADB 连接成功")
            log("")

            ensureRootAdbd()
            remountSystem()
            runScript(scriptPath)
        } catch (e: Exception) {
            log("")
            log("✗ 错误: ${e.message}")
            Log.e(TAG, "Script execution failed", e)
            false
        } finally {
            adbClient.close()
        }
    }

    private fun ensureRootAdbd() {
        val alreadyRoot = adbClient.executeShellCommand("id").contains("uid=0")
        if (alreadyRoot) {
            log("[INFO] adbd 已是 root，跳过 adb root")
            return
        }

        log("[INFO] 执行 adb root ...")
        adbClient.close()
        adbClient.connectLocal()
        adbClient.performHandshake(log)
        log("[INFO]   ${adbClient.executeService("root:")}")
        adbClient.close()
        log("[INFO] 等待 adbd 以 root 身份重启...")
        adbClient.waitForAdbd(8000)
    }

    private fun remountSystem() {
        adbClient.connectLocal()
        if (adbClient.performHandshake(log)) {
            log("[INFO] 执行 adb remount ...")
            log("[INFO]   ${adbClient.executeService("remount:")}")
        } else {
            log("[WARN] remount 握手失败，继续执行脚本")
        }
        adbClient.close()
        log("")
    }

    private fun runScript(scriptPath: String): Boolean {
        adbClient.connectLocal(readTimeoutMs = SCRIPT_TIMEOUT_MS)
        if (!adbClient.performHandshake(log)) {
            log("✗ ADB 连接握手失败")
            return false
        }
        val success = adbClient.executeShellCommandStreaming("sh $scriptPath", log)
        log("")
        log(if (success) "✓ 脚本执行成功" else "✗ 脚本执行过程中出现错误，请检查日志")
        return success
    }

    private companion object {
        private const val TAG = "AdbScriptExecutor"
        private const val RETRY_DELAY_MS = 3000L
        private const val SCRIPT_TIMEOUT_MS = 60000
    }
}
