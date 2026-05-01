package com.kooo.evcam.v2.ui.settings

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.storage.V2StoragePathHelper

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
        }
        row.addView(spinnerRow("存储位置", locationOptions, selectedIndex) { position ->
            val location = if (position == 1) V2StoragePathHelper.StorageLocation.USB else V2StoragePathHelper.StorageLocation.INTERNAL
            V2StoragePathHelper.saveLocation(activity, location)
            onStorageLocationChanged()
        })
        row.addView(cards.cardTexts("当前路径", V2StoragePathHelper.storageSummary(activity), 0, useWeight = false))
        row.addView(reservedSpaceRow())
        return row
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

    private fun spinnerRow(label: String, labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit): View {
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
        val spinner = Spinner(activity).apply {
            adapter = spinnerAdapter(labels)
            setSelection(selectedIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) { initialized = true; return }
                    onSelected(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.setOnClickListener { spinner.performClick() }
        row.addView(spinner, LinearLayout.LayoutParams(cards.dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
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
