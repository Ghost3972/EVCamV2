package com.kooo.evcam.v2.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.storage.V2StorageLocationSettings
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File

internal class V2StorageDebugDialogController(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    fun show(currentPathText: TextView) {
        V2StoragePathHelper.clearCache()
        val hasUsb = V2StoragePathHelper.hasUsbStorage(activity)
        val usbDir = V2StoragePathHelper.availableUsbMount(activity)
        val info = buildString {
            append("=== 存储权限状态 ===\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append("所有文件访问权限: ")
                append(if (Environment.isExternalStorageManager()) "已授权 ✓\n" else "未授权 ✗\n")
            }
            append("存储访问权限: ")
            append(if (hasStoragePermission()) "已授权 ✓\n" else "未授权 ✗\n")
            append("\n=== 当前检测 ===\n")
            append(if (hasUsb) "已检测到U盘 ✓\n" else "未检测到U盘 ✗\n")
            append("U盘写入目录: ${usbDir?.absolutePath ?: "不可用"}\n")
            append("是否回退内部存储: ${if (V2StoragePathHelper.isUsbFallback(activity)) "是" else "否"}\n")
            append("\n")
            V2StoragePathHelper.storageDebugInfo(activity).forEach { append(it).append('\n') }
        }
        AlertDialog.Builder(activity)
            .setTitle("存储设备检测信息")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .setNeutralButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("存储检测信息", info))
                Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("手动设置路径") { _, _ -> showManualUsbPathDialog(currentPathText) }
            .show()
    }

    private fun showManualUsbPathDialog(currentPathText: TextView) {
        val input = EditText(activity).apply {
            hint = "例如: /storage/ABCD-1234"
            setSingleLine(true)
            setText(V2StorageLocationSettings.customUsbPath(activity).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            setPadding(cards.dp(16), cards.dp(8), cards.dp(16), cards.dp(8))
        }
        AlertDialog.Builder(activity)
            .setTitle("手动设置U盘路径")
            .setMessage("自动检测失败时可手动输入U盘挂载路径。\n\n常见格式：/storage/XXXX-XXXX\n留空表示使用自动检测。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val path = input.text?.toString()?.trim().orEmpty()
                if (path.isBlank()) {
                    V2StorageLocationSettings.setCustomUsbPath(activity, null)
                    Toast.makeText(activity, "已清除自定义路径", Toast.LENGTH_SHORT).show()
                } else {
                    val dir = File(path)
                    V2StorageLocationSettings.setCustomUsbPath(activity, path)
                    val warning = when {
                        !dir.exists() -> "路径不存在，但已保存"
                        !dir.isDirectory -> "路径不是目录，但已保存"
                        !dir.canRead() -> "路径不可读，但已保存"
                        !dir.canWrite() -> "路径不可写，但已保存"
                        else -> "U盘路径已设置"
                    }
                    Toast.makeText(activity, warning, Toast.LENGTH_LONG).show()
                }
                V2StoragePathHelper.clearCache()
                currentPathText.text = V2StoragePathHelper.storageSummary(activity)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hasStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
