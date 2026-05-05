package com.kooo.evcam.v2.ui.settings

import android.Manifest
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.permissions.AdbPermissionHelper
import com.kooo.evcam.v2.permissions.SystemWhitelistHelper

object V2PermissionSettingsDialog {
    private var adbRunning = false
    private var whitelistRunning = false
    private var restoreRunning = false

    fun createPageView(
        context: Context,
        showCloseButton: Boolean = false,
        showTitle: Boolean = true,
        onClose: (() -> Unit)? = null
    ): View {
        val views = V2PermissionDialogViews(context)
        val refreshers = mutableListOf<() -> Unit>()
        val refreshAll = { refreshers.forEach { it() } }
        val root = views.rootScroll()
        val content = views.contentColumn()
        root.addView(content)

        if (showTitle) {
            content.addView(views.title("权限设置"))
        }
        content.addView(views.description("请确保以下权限已授予，以保证应用正常运行"))
        content.addView(adbCard(context, views, refreshAll))
        content.addView(views.sectionTitle("基础权限"))
        content.addView(views.permissionRow("相机权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.hasCameraPermission(context), "用于录制视频和预览") }, refreshers) {
            V2PermissionSettingsNavigator.requestRuntimePermissions(context, arrayOf(Manifest.permission.CAMERA))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            content.addView(views.permissionRow("通知权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.hasNotificationPermission(context), "用于显示前台录制服务通知") }, refreshers) {
                V2PermissionSettingsNavigator.requestRuntimePermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            })
            content.addView(views.permissionRow("媒体文件权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.hasMediaPermissions(context), "用于读取录像和图片回放列表") }, refreshers) {
                V2PermissionSettingsNavigator.requestRuntimePermissions(context, V2PermissionStatusChecker.mediaPermissions())
            })
        }

        content.addView(views.sectionTitle("高级权限"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            content.addView(views.permissionRow("所有文件访问权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.hasAllFilesAccess(), "用于访问U盘和公共目录") }, refreshers) {
                V2PermissionSettingsNavigator.openManageAllFiles(context)
            })
        }
        content.addView(views.permissionRow("悬浮窗权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.canDrawOverlays(context), "用于悬浮窗和后台唤醒") }, refreshers) {
            V2PermissionSettingsNavigator.openOverlaySettings(context)
        })
        content.addView(views.permissionRow("无障碍服务", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.isAccessibilityEnabled(context), "防止应用被系统清理") }, refreshers, "去启用") {
            V2PermissionSettingsNavigator.openAccessibilitySettings(context)
        })
        content.addView(views.permissionRow("使用情况访问权限", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.hasUsageStatsPermission(context), "全景/泊车避让需要检测前台应用") }, refreshers) {
            V2PermissionSettingsNavigator.openUsageStatsSettings(context)
        })
        content.addView(views.permissionRow("电池优化", { V2PermissionStatusChecker.statusText(V2PermissionStatusChecker.isIgnoringBatteryOptimizations(context), "关闭可防止应用被系统休眠") }, refreshers, "去设置") {
            V2PermissionSettingsNavigator.requestIgnoreBatteryOptimizations(context)
        })

        content.addView(views.sectionTitle("系统级保活"))
        content.addView(systemWhitelistCard(context, views))
        if (showCloseButton) {
            val closeButton = views.button("关闭", views.color(R.color.button_background)).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = views.dp(8)
                }
            }
            closeButton.setOnClickListener { onClose?.invoke() }
            content.addView(closeButton)
        }
        return root
    }

    private fun adbCard(context: Context, views: V2PermissionDialogViews, refreshAll: () -> Unit): View {
        val log = views.logView()
        val button = views.button("一键获取权限", 0xFF4CAF50.toInt())
        val card = views.verticalCard()
        card.addView(views.cardTitle("ADB 一键获取权限"))
        card.addView(views.cardSubtitle("通过本机 ADB (localhost:5555) 自动授予所有权限"))
        card.addView(button)
        card.addView(log.container)
        button.setOnClickListener {
            if (adbRunning) return@setOnClickListener
            adbRunning = true
            button.isEnabled = false
            button.text = "正在执行..."
            log.show()
            log.text.text = ""
            AdbPermissionHelper(context).grantAllPermissions(object : AdbPermissionHelper.Callback {
                override fun onLog(message: String) = log.append(message)
                override fun onComplete(allSuccess: Boolean) {
                    adbRunning = false
                    button.isEnabled = true
                    button.text = "一键获取权限"
                    refreshAll()
                    Toast.makeText(context, if (allSuccess) "权限获取完成" else "部分权限获取失败", Toast.LENGTH_SHORT).show()
                }
            })
        }
        return card
    }

    private fun systemWhitelistCard(context: Context, views: V2PermissionDialogViews): View {
        val whitelistLog = views.logView()
        val restoreLog = views.logView()
        val setupButton = views.button("一键配置", 0xFF2196F3.toInt())
        val restoreButton = views.button("恢复系统白名单", 0xFFFF9800.toInt()).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = views.dp(8)
        }
        val card = views.verticalCard()
        card.addView(views.cardTitle("银河E5（E245）系统白名单"))
        card.addView(views.cardSubtitle("将 EVCam 添加到车机系统启动列表和后台白名单，防止深度睡眠后被杀"))
        card.addView(setupButton)
        card.addView(whitelistLog.container)
        card.addView(restoreButton)
        card.addView(restoreLog.container)

        setupButton.setOnClickListener {
            showWhitelistRiskDialog(context, views) {
                runWhitelist(context, setupButton, whitelistLog, restore = false)
            }
        }
        restoreButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("恢复确认")
                .setMessage("此操作将从备份恢复车机系统白名单配置，恢复后需要重启车机。确认恢复？")
                .setPositiveButton("确认恢复") { _, _ -> runWhitelist(context, restoreButton, restoreLog, restore = true) }
                .setNegativeButton("取消", null)
                .show()
        }
        return card
    }

    private fun runWhitelist(context: Context, button: Button, log: V2PermissionLogViews, restore: Boolean) {
        if ((restore && restoreRunning) || (!restore && whitelistRunning)) return
        if (restore) restoreRunning = true else whitelistRunning = true
        button.isEnabled = false
        button.text = if (restore) "正在恢复..." else "正在执行..."
        log.show()
        log.text.text = ""
        val helper = SystemWhitelistHelper(context)
        val callback = object : SystemWhitelistHelper.Callback {
            override fun onLog(message: String) = log.append(message)
            override fun onComplete(success: Boolean) {
                if (restore) restoreRunning = false else whitelistRunning = false
                button.isEnabled = true
                button.text = if (restore) "恢复系统白名单" else "一键配置"
                Toast.makeText(context, if (success) "执行完成，请重启车机" else "执行失败，请查看日志", Toast.LENGTH_SHORT).show()
            }
        }
        if (restore) helper.executeWhitelistRestore(callback) else helper.executeWhitelistSetup(callback)
    }

    private fun showWhitelistRiskDialog(context: Context, views: V2PermissionDialogViews, onConfirmed: () -> Unit) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(views.dp(20), views.dp(16), views.dp(20), views.dp(16))
            background = views.cardBackground()
        }
        box.addView(views.title("风险提醒"))
        box.addView(TextView(context).apply {
            text = "此操作将修改车机系统分区配置文件，请仔细确认：\n\n" +
                "1. 仅适用于银河E5（E245）车机\n" +
                "2. 需要设备已打开USB调试\n" +
                "3. 将修改 system/vendor 分区配置文件\n" +
                "4. 修改前会自动备份原文件\n" +
                "5. 修改完成后需要重启车机才能生效\n" +
                "6. 如果设备不是 E245，脚本会自动检测并中止"
            setTextColor(views.color(R.color.text_primary))
            textSize = 14f
        })
        val check = CheckBox(context).apply {
            text = "我已知晓风险，确认继续"
            textSize = 20f
            includeFontPadding = false
            minHeight = views.dp(48)
            buttonDrawable = ContextCompat.getDrawable(context, R.drawable.v2_settings_checkbox_selector)
            setPadding(views.dp(2), views.dp(10), views.dp(18), views.dp(10))
            setTextColor(views.color(R.color.settings_title_primary))
        }
        box.addView(check)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, views.dp(8), 0, 0)
        }
        val cancel = views.button("取消", views.color(R.color.button_background)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = views.dp(8)
            }
        }
        val confirm = views.button("确认执行", 0xFF2196F3.toInt()).apply {
            isEnabled = false
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttons.addView(cancel)
        buttons.addView(confirm)
        box.addView(buttons)

        val dialog = AlertDialog.Builder(context)
            .setView(box)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        cancel.setOnClickListener { dialog.dismiss() }
        check.setOnCheckedChangeListener { _, checked ->
            confirm.isEnabled = checked
            confirm.alpha = if (checked) 1f else 0.45f
        }
        confirm.setOnClickListener {
            if (!check.isChecked) return@setOnClickListener
            dialog.dismiss()
            onConfirmed()
        }
    }
}
