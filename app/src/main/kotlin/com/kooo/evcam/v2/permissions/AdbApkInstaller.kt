package com.kooo.evcam.v2.permissions

import android.util.Log
import java.net.SocketTimeoutException

internal class AdbApkInstaller(
    private val adbClient: AdbTcpClient,
    private val connectAdb: () -> Boolean,
    private val log: (String) -> Unit,
) {
    fun install(apkPath: String): Boolean {
        log("=== ADB 安装更新 ===")
        try {
            if (!connectAdb()) return false

            log("✓ ADB 连接成功")
            log("")
            val installPath = apkPath.toInstallPath()
            log("正在安装...")
            log("  $ pm install -r $installPath")
            log("  (安装过程可能需要 30-60 秒，请耐心等待)")

            adbClient.setReadTimeout(AdbTcpClient.INSTALL_TIMEOUT_MS)
            return try {
                val result = adbClient.executeShellCommand("pm install -r $installPath").trim()
                if (result.lowercase().contains("success")) {
                    log("")
                    log("✓ 安装成功！")
                    log("  应用即将自动重启...")
                    true
                } else {
                    log("")
                    log("✗ 安装失败: $result")
                    log("  请尝试手动安装")
                    false
                }
            } finally {
                adbClient.setReadTimeout(ADB_READ_TIMEOUT_MS)
            }
        } catch (error: SocketTimeoutException) {
            log("")
            log("✗ 安装超时 (超过 120 秒)")
            log("  请尝试手动安装")
            Log.e(TAG, "ADB install timeout", error)
            return false
        } catch (error: Exception) {
            log("")
            log("✗ 错误: ${error.message}")
            Log.e(TAG, "ADB install failed", error)
            return false
        }
    }

    private fun String.toInstallPath(): String {
        return if (startsWith(EMULATED_STORAGE_PREFIX)) {
            DATA_MEDIA_PREFIX + substring(EMULATED_STORAGE_PREFIX.length)
        } else {
            this
        }
    }

    private companion object {
        private const val TAG = "AdbPermissionHelper"
        private const val EMULATED_STORAGE_PREFIX = "/storage/emulated/"
        private const val DATA_MEDIA_PREFIX = "/data/media/"
    }
}
