package com.kooo.evcam.v2.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.storage.V2StoragePathHelper

class V2StorageSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private val debugDialogs = V2StorageDebugDialogController(activity, cards)

    fun create(): View {
        val row = cards.cardContainer()
        val header = cards.cardTexts(
            "空间清理",
            storageSubtitle(),
            0,
            useWeight = false
        )
        val summaryText = header.getChildAt(1) as TextView
        row.addView(header)
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
        row.addView(reservedSpaceRow { summaryText.text = storageSubtitle() })
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
        row.addView(cards.actionButton("检测", { debugDialogs.show(currentPathText) }, minWidthDp = 140, minHeightDp = 72), LinearLayout.LayoutParams(cards.dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun reservedSpaceRow(onChanged: () -> Unit): View {
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
            setBackgroundResource(R.drawable.v2_settings_field_bg)
            setPadding(cards.dp(14), cards.dp(12), cards.dp(14), cards.dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.trim()?.toIntOrNull() ?: 0
                    V2StorageCleanupSettings.setReservedSpaceGb(activity, value)
                    onChanged()
                }
            })
        }, LinearLayout.LayoutParams(cards.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        return inputRow
    }

    private fun storageSubtitle(): String =
        "设置预留空间；录像分段开始前检测，可用空间低于该值时滚动覆盖最旧录像。当前仍按‘可用空间低于预留值时删除最旧录像’逻辑执行。\n${V2StorageCleanupSettings.summary(activity)}"

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
        val dropdown = cards.dropdownField(
            labels = labels,
            selectedIndex = selectedIndex,
            onSelected = { },
            canSelect = { position -> onSelected(position) },
            widthDp = 240,
        )
        row.addView(dropdown, LinearLayout.LayoutParams(cards.dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { dropdown.performClick() }
        return row
    }

    private fun hasPublicStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

}
