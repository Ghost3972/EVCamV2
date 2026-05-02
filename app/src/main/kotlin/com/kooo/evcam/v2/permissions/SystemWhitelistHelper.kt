package com.kooo.evcam.v2.permissions

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 银河E5（E245）系统白名单配置助手。
 * 通过 ADB TCP 协议执行 assets 中的配置/恢复脚本。
 */
class SystemWhitelistHelper(context: Context) {
    private val context = context.applicationContext
    private var adbHelper: AdbPermissionHelper? = null

    interface Callback {
        fun onLog(message: String)
        fun onComplete(success: Boolean)
    }

    fun executeWhitelistSetup(callback: Callback) = executeScriptAsset(
        assetName = SCRIPT_ASSET_NAME,
        preparingMessage = "[INFO] 正在准备脚本文件...",
        prepareErrorMessage = "[ERROR] 无法准备脚本文件",
        callback = callback,
    )

    fun executeWhitelistRestore(callback: Callback) = executeScriptAsset(
        assetName = RESTORE_SCRIPT_ASSET_NAME,
        preparingMessage = "[INFO] 正在准备恢复脚本...",
        prepareErrorMessage = "[ERROR] 无法准备恢复脚本",
        callback = callback,
    )

    private fun executeScriptAsset(
        assetName: String,
        preparingMessage: String,
        prepareErrorMessage: String,
        callback: Callback,
    ) {
        callback.onLog(preparingMessage)
        val scriptFile = copyScriptFromAssets(assetName)
        if (scriptFile == null) {
            callback.onLog(prepareErrorMessage)
            callback.onComplete(false)
            return
        }
        callback.onLog("[OK] 脚本已准备: ${scriptFile.absolutePath}")
        callback.onLog("")

        val helper = adbHelper ?: AdbPermissionHelper(context).also { adbHelper = it }
        helper.executeScriptFile(scriptFile.absolutePath, object : AdbPermissionHelper.Callback {
            override fun onLog(message: String) = callback.onLog(message)

            override fun onComplete(allSuccess: Boolean) {
                if (scriptFile.exists()) scriptFile.delete()
                callback.onComplete(allSuccess)
            }
        })
    }

    private fun copyScriptFromAssets(assetName: String): File? {
        val scriptFile = File(context.cacheDir, assetName)
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(scriptFile).use { output ->
                    input.copyTo(output, bufferSize = 4096)
                    output.flush()
                }
            }
            scriptFile.setReadable(true, false)
            scriptFile
        } catch (error: IOException) {
            Log.e(TAG, "复制脚本文件失败", error)
            null
        }
    }

    companion object {
        private const val TAG = "SystemWhitelistHelper"
        private const val SCRIPT_ASSET_NAME = "add_evcam_config.sh"
        private const val RESTORE_SCRIPT_ASSET_NAME = "restore_evcam_config.sh"
    }
}
