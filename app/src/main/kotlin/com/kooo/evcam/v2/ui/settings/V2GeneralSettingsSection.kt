package com.kooo.evcam.v2.ui.settings

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.permissions.AdbPermissionHelper
import com.kooo.evcam.v2.service.V2KeepAliveStatus
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.settings.V2VehicleModelSettings
import com.kooo.evcam.v2.update.V2VersionUpdateManager
import java.io.File

class V2GeneralSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
    private val onRefreshHome: () -> Unit
) {
    private val updateManager by lazy { V2VersionUpdateManager(activity) }

    fun versionCard(): View = cards.entryCard(
        title = "版本信息",
        subtitle = "EVCam V2\n版本：${versionName()}\n包名：${activity.packageName}",
        buttonText = "检查 →",
        onClick = { checkUpdate() }
    )

    fun keepAliveStatusCard(): View = cards.entryCard(
        title = "保活状态",
        subtitle = V2KeepAliveStatus.summary(activity),
        buttonText = "刷新 →",
        onClick = onRefreshHome
    )

    fun logExportCard(): View = cards.entryCard(
        title = "保存日志",
        subtitle = "保存本次运行日志，路径沿用旧版 EVCam_Log 目录设计",
        buttonText = "保存 →",
        onClick = { saveLogs() }
    )

    fun vehicleModelCard(): View {
        val models = V2VehicleModelSettings.models
        val currentIndex = models.indexOfFirst { it.id == V2VehicleModelSettings.getModelId(activity) }.coerceAtLeast(0)
        val row = cards.cardRow()
        val texts = cards.cardTexts(
            "车型配置",
            V2VehicleModelSettings.mappingSummary(activity) + "\n使用当前预览布局，仅切换前后左右摄像头映射；更改后重启应用生效"
        )

        var initialized = false
        val spinner = Spinner(activity).apply {
            adapter = vehicleModelAdapter(models.map { it.label })
            setSelection(currentIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) {
                        initialized = true
                        return
                    }
                    V2VehicleModelSettings.setModelId(activity, models[position].id)
                    V2AppLog.i(TAG, "vehicle model changed to ${models[position].label} ${V2VehicleModelSettings.mappingSummary(activity).replace('\n', ' ')}")
                    Toast.makeText(activity, "需重启生效", Toast.LENGTH_SHORT).show()
                    onRefreshHome()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.setOnClickListener { spinner.performClick() }
        row.addView(texts)
        row.addView(spinner, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun startupSwitchCard(): View = cards.switchCard(
        title = "开机自启动",
        subtitle = "车机开机后自动启动 EVCam V2",
        checked = V2StartupSettings.isAutoStartOnBoot(activity),
        onCheckedChange = { enabled ->
            V2StartupSettings.setAutoStartOnBoot(activity, enabled)
            V2AppLog.i(TAG, "autoStartOnBoot=$enabled")
        }
    )

    fun recordingSwitchCard(): View = cards.switchCard(
        title = "自动录制",
        subtitle = "软件启动后立即自动开始录制；开机自启动时同样生效",
        checked = V2StartupSettings.isAutoStartRecording(activity),
        onCheckedChange = { enabled ->
            V2StartupSettings.setAutoStartRecording(activity, enabled)
            V2AppLog.i(TAG, "autoStartRecording=$enabled")
        }
    )

    private fun saveLogs() {
        V2AppLog.i(TAG, "manual log export requested")
        val file = V2AppLog.exportCurrentLogs(activity)
        if (file != null) {
            Toast.makeText(activity, "日志已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
            V2AppLog.i(TAG, "manual log exported: ${file.absolutePath}")
        } else {
            Toast.makeText(activity, "暂无日志可保存", Toast.LENGTH_SHORT).show()
            V2AppLog.w(TAG, "manual log export skipped: empty buffer")
        }
    }

    private fun checkUpdate() {
        Toast.makeText(activity, "正在检查更新...", Toast.LENGTH_SHORT).show()
        updateManager.checkUpdate(object : V2VersionUpdateManager.UpdateCheckCallback {
            override fun onUpdateAvailable(info: V2VersionUpdateManager.UpdateInfo) {
                val message = buildString {
                    append("当前版本：v${updateManager.currentVersionName()}\n")
                    append("最新版本：v${info.versionName}\n")
                    if (info.notes.isNotBlank()) append("\n更新说明：\n${info.notes}")
                    append("\n\n是否下载更新？")
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
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
        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
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
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
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

    private fun vehicleModelAdapter(labels: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getView(position, convertView, parent) as TextView, dropdown = false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getDropDownView(position, convertView, parent) as TextView, dropdown = true)

        private fun styledText(view: TextView, dropdown: Boolean): TextView = view.apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(activity, if (dropdown) R.color.card_background else R.color.input_background))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    private fun dp(value: Int): Int = cards.dp(value)

    private companion object {
        const val TAG = "V2SettingsActivity"
    }
}
