package com.kooo.evcam.v2.ui.settings

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2CustomKeySettings
import com.kooo.evcam.v2.settings.V2SettingsCategory
import java.util.Locale

class V2SignalSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private var blindSpotCorrectionPreviewSide: String? = null

    fun blindSpotCard(): View {
        val card = propIdSwitchCard(
            title = "转向补盲",
            subtitle = "监听 VHAL 转向灯属性；左=${V2BlindSpotSettings.LEFT_VALUE} 右=${V2BlindSpotSettings.RIGHT_VALUE} 关=${V2BlindSpotSettings.OFF_VALUE}；归零稳定 ${V2BlindSpotSettings.HIDE_DELAY_MS / 1000} 秒后关闭悬浮窗",
            propId = V2BlindSpotSettings.turnSignalPropId(activity),
            defaultPropId = V2BlindSpotSettings.DEFAULT_TURN_SIGNAL_PROP_ID,
            checked = V2BlindSpotSettings.isEnabled(activity),
            invalidToast = "转向灯属性ID无效",
            successToast = "补盲设置已生效",
            logPrefix = "blindSpot",
            settingsCategory = V2SettingsCategory.BLIND_SPOT,
            propIdReader = { V2BlindSpotSettings.turnSignalPropId(activity) },
            propIdWriter = { V2BlindSpotSettings.setTurnSignalPropId(activity, it) },
            enabledWriter = { V2BlindSpotSettings.setEnabled(activity, it) }
        ) { enabled ->
            blindSpotCorrectionSection().apply {
                visibility = if (enabled) View.VISIBLE else View.GONE
            }
        }
        return card
    }

    fun customKeyCard(): View = propIdSwitchCard(
        title = "定制键调出/隐藏",
        subtitle = "监听 VHAL 按钮属性值变为 4 时切换主界面显示状态",
        propId = V2CustomKeySettings.buttonPropId(activity),
        defaultPropId = V2CustomKeySettings.DEFAULT_BUTTON_PROP_ID,
        checked = V2CustomKeySettings.isEnabled(activity),
        invalidToast = "属性ID无效",
        successToast = "定制键设置已生效",
        logPrefix = "customKey",
        settingsCategory = V2SettingsCategory.CUSTOM_KEY,
        propIdReader = { V2CustomKeySettings.buttonPropId(activity) },
        propIdWriter = { V2CustomKeySettings.setButtonPropId(activity, it) },
        enabledWriter = { V2CustomKeySettings.setEnabled(activity, it) }
    )

    private fun blindSpotCorrectionSection(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(cards.cardTexts(
            title = "补盲画面矫正",
            subtitle = "左右独立缩放、平移、镜像；点击预览后拖动参数可实时查看效果",
            useWeight = false
        ))
        val paramsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (V2BlindSpotSettings.isCorrectionEnabled(activity)) View.VISIBLE else View.GONE
        }
        fun rebuildParams() {
            paramsContainer.removeAllViews()
            paramsContainer.addView(correctionResetButton { rebuildParams() })
            paramsContainer.addView(blindSpotCorrectionSideSection("left", "左侧摄像头"))
            paramsContainer.addView(blindSpotCorrectionSideSection("right", "右侧摄像头"))
        }
        card.addView(correctionEnableRow(paramsContainer))
        card.addView(paramsContainer)
        rebuildParams()
        return card
    }

    private fun propIdSwitchCard(
        title: String,
        subtitle: String,
        propId: Int,
        defaultPropId: Int,
        checked: Boolean,
        invalidToast: String,
        successToast: String,
        logPrefix: String,
        settingsCategory: String,
        propIdReader: () -> Int,
        propIdWriter: (Int) -> Unit,
        enabledWriter: (Boolean) -> Unit,
        extraContent: ((Boolean) -> View)? = null
    ): View {
        val row = cards.cardContainer()
        row.addView(cards.cardTexts(title, subtitle, useWeight = false))
        val propEdit = EditText(activity).apply {
            setText(propId.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            hint = defaultPropId.toString()
        }
        val switch = Switch(activity).apply { isChecked = checked }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(propEdit, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        controls.addView(switch)

        fun saveAndRefresh(showToast: Boolean) {
            val inputPropId = propEdit.text?.toString()?.trim()?.toIntOrNull()
            if (inputPropId == null || inputPropId <= 0) {
                V2AppLog.w(TAG, "invalid $logPrefix propId input=${propEdit.text}")
                Toast.makeText(activity, invalidToast, Toast.LENGTH_SHORT).show()
                propEdit.setText(propIdReader().toString())
                return
            }
            propIdWriter(inputPropId)
            enabledWriter(switch.isChecked)
            V2AppLog.i(TAG, "$logPrefix enabled=${switch.isChecked} propId=$inputPropId")
            V2CameraServiceCommands.notifySettingsChanged(activity, settingsCategory)
            if (showToast) Toast.makeText(activity, successToast, Toast.LENGTH_SHORT).show()
        }

        val extraView = extraContent?.invoke(checked)
        switch.setOnCheckedChangeListener { _, isChecked ->
            extraView?.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveAndRefresh(true)
        }
        propEdit.setOnEditorActionListener { _, _, _ -> saveAndRefresh(true); true }
        propEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAndRefresh(false) }
        row.setOnClickListener { switch.toggle() }
        row.addView(controls)
        if (extraView != null) row.addView(extraView)
        return row
    }

    private fun correctionEnableRow(paramsContainer: View): View {
        val enableRow = cards.switchRow()
        enableRow.addView(TextView(activity).apply {
            text = "启用画面矫正"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enableSwitch = Switch(activity).apply {
            isChecked = V2BlindSpotSettings.isCorrectionEnabled(activity)
            setOnCheckedChangeListener { _, enabled ->
                V2BlindSpotSettings.setCorrectionEnabled(activity, enabled)
                paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
                V2CameraServiceCommands.refreshBlindSpot(activity)
            }
        }
        enableRow.setOnClickListener { enableSwitch.toggle() }
        enableRow.addView(enableSwitch)
        return enableRow
    }

    private fun correctionResetButton(onReset: () -> Unit): View = Button(activity).apply {
        text = "恢复默认参数"
        textSize = 14f
        minHeight = dp(44)
        setOnClickListener {
            V2BlindSpotSettings.resetAllCorrections(activity)
            blindSpotCorrectionPreviewSide?.let { side -> V2CameraServiceCommands.showBlindSpotPreview(activity, side) }
            Toast.makeText(activity, "补盲矫正参数已恢复默认", Toast.LENGTH_SHORT).show()
            onReset()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun blindSpotCorrectionSideSection(side: String, title: String): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Button(activity).apply {
            text = "预览"
            textSize = 14f
            minHeight = dp(40)
            setOnClickListener {
                blindSpotCorrectionPreviewSide = side
                V2CameraServiceCommands.showBlindSpotPreview(activity, side)
            }
        }, LinearLayout.LayoutParams(dp(86), dp(44)))
        container.addView(titleRow)

        var current = V2BlindSpotSettings.correction(activity, side)
        fun save(next: V2BlindSpotCorrection) {
            current = next
            V2BlindSpotSettings.setCorrection(activity, side, next)
            if (blindSpotCorrectionPreviewSide == side) {
                V2CameraServiceCommands.showBlindSpotPreview(activity, side)
            }
        }
        container.addView(sliderRow("缩放X", 0.5f, 2.0f, current.scaleX) { save(current.copy(scaleX = it)) })
        container.addView(sliderRow("缩放Y", 0.5f, 2.0f, current.scaleY) { save(current.copy(scaleY = it)) })
        container.addView(sliderRow("平移X", -1.0f, 1.0f, current.translateX) { save(current.copy(translateX = it)) })
        container.addView(sliderRow("平移Y", -1.0f, 1.0f, current.translateY) { save(current.copy(translateY = it)) })
        container.addView(sliderRow("旋转", 0f, 360f, current.rotation) { save(current.copy(rotation = it)) })
        container.addView(correctionMirrorRow("水平镜像", current.mirrorH) { save(current.copy(mirrorH = it)) })
        container.addView(correctionMirrorRow("垂直镜像", current.mirrorV) { save(current.copy(mirrorV = it)) })
        return container
    }

    private fun correctionMirrorRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val line = cards.switchRow()
        line.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> onChanged(enabled) }
        }
        line.setOnClickListener { toggle.toggle() }
        line.addView(toggle)
        return line
    }

    private fun sliderRow(label: String, min: Float, rangeMax: Float, value: Float, onChanged: (Float) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val valueText = TextView(activity).apply {
            text = "$label ${formatParam(value)}"
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val seekBar = SeekBar(activity).apply {
            max = 1000
            progress = (((value - min) / (rangeMax - min)) * max).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = min + (rangeMax - min) * progress / 1000f
                    valueText.text = "$label ${formatParam(next)}"
                    onChanged(formatParam(next).toFloat())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        row.addView(valueText, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun formatParam(value: Float): String = String.format(Locale.US, "%.2f", value)
    private fun dp(value: Int): Int = cards.dp(value)

    private companion object {
        const val TAG = "V2SettingsActivity"
    }
}
