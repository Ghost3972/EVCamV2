package com.kooo.evcam.v2.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.Manifest
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.storage.V2StorageLocationSettings
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File

class V2StorageSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
    private val onStorageLocationChanged: () -> Unit,
) {
    fun create(): View {
        val row = cards.cardContainer()
        row.addView(cards.cardTexts(
            "空间清理",
            "设置预留空间；录像分段开始前检测，可用空间低于该值时滚动覆盖最旧录像。当前仍按‘可用空间低于预留值时删除最旧录像’逻辑执行。\n${V2StorageCleanupSettings.summary(activity)}",
            0,
            useWeight = false
        ))
        val locationOptions = V2StoragePathHelper.storageOptions(activity)
        val selectedIndex = when (V2StoragePathHelper.preferredLocation(activity)) {
            V2StoragePathHelper.StorageLocation.INTERNAL -> 0
            V2StoragePathHelper.StorageLocation.USB -> 1
            V2StoragePathHelper.StorageLocation.PUBLIC_DCIM -> 2
        }
        val currentPathText = TextView(activity).apply {
            text = V2StoragePathHelper.storageSummary(activity)
            textSize = 14f
            setPadding(0, cards.dp(4), 0, 0)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        }
        row.addView(spinnerRow("存储位置", locationOptions, selectedIndex) { position ->
            val location = when (position) {
                1 -> V2StoragePathHelper.StorageLocation.USB
                2 -> V2StoragePathHelper.StorageLocation.PUBLIC_DCIM
                else -> V2StoragePathHelper.StorageLocation.INTERNAL
            }
            if (location == V2StoragePathHelper.StorageLocation.USB && V2StoragePathHelper.availableUsbMount(activity) == null) {
                Toast.makeText(activity, "未检测到U盘", Toast.LENGTH_SHORT).show()
                return@spinnerRow false
            }
            if (location == V2StoragePathHelper.StorageLocation.PUBLIC_DCIM && !hasPublicStoragePermission()) {
                Toast.makeText(activity, "无存储权限", Toast.LENGTH_SHORT).show()
                return@spinnerRow false
            }
            V2StoragePathHelper.saveLocation(activity, location)
            currentPathText.text = V2StoragePathHelper.storageSummary(activity)
            true
        })
        row.addView(cards.cardTexts("当前路径", "", 0, useWeight = false).apply {
            addView(currentPathText)
        })
        row.addView(usbDetectionRow(currentPathText))
        row.addView(reservedSpaceRow())
        return row
    }

    private fun usbDetectionRow(currentPathText: TextView): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cards.dp(8), 0, cards.dp(8))
        }
        row.addView(TextView(activity).apply {
            text = "U盘检测"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(activity).apply {
            text = "检测"
            textSize = 16f
            minHeight = cards.dp(44)
            setOnClickListener { showStorageDebugInfo(currentPathText) }
        }, LinearLayout.LayoutParams(cards.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun showStorageDebugInfo(currentPathText: TextView) {
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

    private fun reservedSpaceRow(): View {
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cards.dp(8), 0, 0)
        }
        inputRow.addView(TextView(activity).apply {
            text = "预留空间(GB)"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(EditText(activity).apply {
            setText(V2StorageCleanupSettings.reservedSpaceGb(activity).toString())
            setSingleLine(true)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.trim()?.toIntOrNull() ?: 0
                    V2StorageCleanupSettings.setReservedSpaceGb(activity, value)
                }
            })
        }, LinearLayout.LayoutParams(cards.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        return inputRow
    }

    private fun spinnerRow(label: String, labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, cards.dp(8), 0, cards.dp(8))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var initialized = false
        var suppressSelection = false
        var currentIndex = selectedIndex
        val spinner = Spinner(activity).apply {
            adapter = spinnerAdapter(labels)
            setSelection(selectedIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) { initialized = true; return }
                    if (suppressSelection) return
                    if (onSelected(position)) {
                        currentIndex = position
                    } else {
                        suppressSelection = true
                        setSelection(currentIndex)
                        suppressSelection = false
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.setOnClickListener { spinner.performClick() }
        row.addView(spinner, LinearLayout.LayoutParams(cards.dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun hasPublicStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun spinnerAdapter(labels: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getView(position, convertView, parent) as TextView, false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getDropDownView(position, convertView, parent) as TextView, true)
        private fun styledText(view: TextView, dropdown: Boolean) = view.apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(activity, if (dropdown) R.color.card_background else R.color.input_background))
            setPadding(cards.dp(12), cards.dp(10), cards.dp(12), cards.dp(10))
        }
    }
}
