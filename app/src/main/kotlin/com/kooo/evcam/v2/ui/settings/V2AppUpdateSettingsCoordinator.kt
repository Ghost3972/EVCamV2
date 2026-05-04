package com.kooo.evcam.v2.ui.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kooo.evcam.R
import com.kooo.evcam.v2.permissions.AdbPermissionHelper
import com.kooo.evcam.v2.update.V2VersionUpdateManager
import java.io.File

internal class V2AppUpdateSettingsCoordinator(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private val updateManager by lazy { V2VersionUpdateManager(activity) }

    fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    fun checkUpdate() {
        Toast.makeText(activity, "正在检查更新...", Toast.LENGTH_SHORT).show()
        updateManager.checkUpdate(object : V2VersionUpdateManager.UpdateCheckCallback {
            override fun onUpdateAvailable(info: V2VersionUpdateManager.UpdateInfo) {
                val message = buildString {
                    append("当前版本：v${updateManager.currentVersionName()}\n")
                    append("最新版本：v${info.versionName}\n")
                    if (info.notes.isNotBlank()) append("\n更新说明：\n${info.notes}")
                    append("\n\n是否下载更新？")
                }
                MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("发现新版本")
                    .setMessage(message)
                    .setPositiveButton("下载更新") { _, _ -> downloadUpdate(info) }
                    .setNegativeButton("稍后再说", null)
                    .show()
            }

            override fun onNoUpdate(remoteVersion: String) {
                Toast.makeText(activity, "已是最新版本：v$remoteVersion", Toast.LENGTH_SHORT).show()
            }

            override fun onError(error: String) {
                Toast.makeText(activity, "检查更新失败：$error", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun downloadUpdate(info: V2VersionUpdateManager.UpdateInfo) {
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(TextView(activity).apply {
                text = "正在下载 EVCam v${info.versionName}..."
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            })
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        val progressDialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
            .setTitle("下载更新")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("取消") { dialog, _ ->
                updateManager.cancelDownload()
                dialog.dismiss()
            }
            .show()

        updateManager.downloadApk(info, object : V2VersionUpdateManager.DownloadCallback {
            override fun onProgress(progress: Int) {
                progressBar.progress = progress
            }

            override fun onComplete(apkFile: File) {
                progressDialog.dismiss()
                showInstallDialog(apkFile, info.versionName)
            }

            override fun onError(error: String) {
                progressDialog.dismiss()
                if (error != "下载已取消") Toast.makeText(activity, "下载失败：$error", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showInstallDialog(apkFile: File, versionName: String) {
        MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
            .setTitle("下载完成")
            .setMessage("EVCam v$versionName 已下载完成。\n\n文件位置：${apkFile.absolutePath}\n\n请选择安装方式：")
            .setPositiveButton("ADB 安装") { _, _ -> installByAdb(apkFile) }
            .setNeutralButton("手动安装") { _, _ -> installManually(apkFile) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun installByAdb(apkFile: File) {
        val scrollView = ScrollView(activity)
        val logView = TextView(activity).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        scrollView.addView(logView)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
            .setTitle("ADB 安装更新")
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false

        AdbPermissionHelper(activity).installApk(apkFile.absolutePath, object : AdbPermissionHelper.Callback {
            override fun onLog(message: String) {
                logView.append(message + "\n")
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }

            override fun onComplete(allSuccess: Boolean) {
                if (dialog.isShowing) dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                if (!allSuccess) Toast.makeText(activity, "ADB 安装失败，请尝试手动安装", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun installManually(apkFile: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .getOrElse { Toast.makeText(activity, "无法打开安装器：${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun dp(value: Int): Int = cards.dp(value)
}
