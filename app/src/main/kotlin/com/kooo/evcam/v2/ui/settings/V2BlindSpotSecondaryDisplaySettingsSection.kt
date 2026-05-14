package com.kooo.evcam.v2.ui.settings

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.kooo.evcam.R
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2SettingsCategory

internal class V2BlindSpotSecondaryDisplaySettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    fun create(visible: Boolean): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        card.addView(cards.cardTexts(
            title = "副屏补盲",
            subtitle = "补盲触发时在副屏指定位置同步显示补盲画面",
            useWeight = false,
        ))

        val paramsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (V2BlindSpotSettings.isSecondaryDisplayEnabled(activity)) View.VISIBLE else View.GONE
        }

        card.addView(enableRow(paramsContainer))
        buildParams(paramsContainer)
        card.addView(paramsContainer)
        return card
    }

    private fun enableRow(paramsContainer: View): View {
        val row = cards.switchRow()
        row.addView(TextView(activity).apply {
            text = "启用副屏补盲"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = cards.settingSwitch(V2BlindSpotSettings.isSecondaryDisplayEnabled(activity)) { enabled ->
            V2BlindSpotSettings.setSecondaryDisplayEnabled(activity, enabled)
            paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
            V2CameraServiceCommands.notifySettingsChanged(activity, V2SettingsCategory.BLIND_SPOT)
        }
        row.addView(switch)
        return row
    }

    private fun buildParams(container: LinearLayout) {
        container.addView(intInputRow("副屏 Display ID", V2BlindSpotSettings.secondaryDisplayId(activity)) { value ->
            V2BlindSpotSettings.setSecondaryDisplayId(activity, value)
            notifyChanged()
        })

        container.addView(rotationRow())

        container.addView(intInputRow("位置 X", V2BlindSpotSettings.secondaryDisplayX(activity)) { value ->
            saveBounds(x = value)
        })
        container.addView(intInputRow("位置 Y", V2BlindSpotSettings.secondaryDisplayY(activity)) { value ->
            saveBounds(y = value)
        })
        container.addView(intInputRow("宽度", V2BlindSpotSettings.secondaryDisplayWidth(activity)) { value ->
            saveBounds(width = value)
        })
        container.addView(intInputRow("高度", V2BlindSpotSettings.secondaryDisplayHeight(activity)) { value ->
            saveBounds(height = value)
        })

        container.addView(borderRow())
        container.addView(previewButton())
    }

    private fun rotationRow(): View {
        val labels = listOf("0°", "90°", "180°", "270°")
        val values = listOf(0, 90, 180, 270)
        val current = V2BlindSpotSettings.secondaryDisplayRotation(activity)
        val selected = values.indexOf(current).coerceAtLeast(0)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(activity).apply {
            text = "旋转角度"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val dropdown = cards.dropdownField(
            labels = labels,
            selectedIndex = selected,
            onSelected = { position ->
                V2BlindSpotSettings.setSecondaryDisplayRotation(activity, values[position])
                notifyChanged()
            },
            widthDp = 160,
        )
        row.addView(dropdown, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun borderRow(): View {
        val row = cards.switchRow()
        row.addView(TextView(activity).apply {
            text = "白色边框"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = cards.settingSwitch(V2BlindSpotSettings.isSecondaryDisplayBorderEnabled(activity)) { enabled ->
            V2BlindSpotSettings.setSecondaryDisplayBorderEnabled(activity, enabled)
            notifyChanged()
        }
        row.addView(switch)
        return row
    }

    private fun previewButton(): View = cards.actionButton("预览副屏补盲", {
        V2CameraServiceCommands.showBlindSpotPreview(activity, "left")
        Toast.makeText(activity, "已触发补盲预览", Toast.LENGTH_SHORT).show()
    }, minWidthDp = 220, minHeightDp = 80).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun intInputRow(label: String, currentValue: Int, onChanged: (Int) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val input = EditText(activity).apply {
            setText(currentValue.toString())
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.v2_settings_field_bg)
            setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        input.doAfterTextChanged {
            val value = it?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            onChanged(value)
        }
        row.addView(input)
        return row
    }

    private fun saveBounds(
        x: Int = V2BlindSpotSettings.secondaryDisplayX(activity),
        y: Int = V2BlindSpotSettings.secondaryDisplayY(activity),
        width: Int = V2BlindSpotSettings.secondaryDisplayWidth(activity),
        height: Int = V2BlindSpotSettings.secondaryDisplayHeight(activity),
    ) {
        V2BlindSpotSettings.setSecondaryDisplayBounds(activity, x, y, width, height)
        notifyChanged()
    }

    private fun notifyChanged() {
        V2CameraServiceCommands.notifySettingsChanged(activity, V2SettingsCategory.BLIND_SPOT)
    }

    private fun dp(value: Int): Int = cards.dp(value)
}
