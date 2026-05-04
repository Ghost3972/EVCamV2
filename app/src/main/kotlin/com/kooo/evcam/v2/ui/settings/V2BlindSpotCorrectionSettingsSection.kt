package com.kooo.evcam.v2.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import java.util.Locale

internal class V2BlindSpotCorrectionSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private var previewSide: String? = null

    fun create(visible: Boolean): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            visibility = if (visible) View.VISIBLE else View.GONE
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
            paramsContainer.addView(resetButton { rebuildParams() })
            paramsContainer.addView(sideSection("left", "左侧摄像头"))
            paramsContainer.addView(sideSection("right", "右侧摄像头"))
        }
        card.addView(enableRow(paramsContainer))
        card.addView(paramsContainer)
        rebuildParams()
        return card
    }

    private fun enableRow(paramsContainer: View): View {
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

    private fun resetButton(onReset: () -> Unit): View = Button(activity).apply {
        text = "恢复默认参数"
        textSize = 14f
        minHeight = dp(44)
        setOnClickListener {
            V2BlindSpotSettings.resetAllCorrections(activity)
            previewSide?.let { side -> V2CameraServiceCommands.showBlindSpotPreview(activity, side) }
            Toast.makeText(activity, "补盲矫正参数已恢复默认", Toast.LENGTH_SHORT).show()
            onReset()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun sideSection(side: String, title: String): View {
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
                previewSide = side
                V2CameraServiceCommands.showBlindSpotPreview(activity, side)
            }
        }, LinearLayout.LayoutParams(dp(86), dp(44)))
        container.addView(titleRow)

        var current = V2BlindSpotSettings.correction(activity, side)
        fun save(next: V2BlindSpotCorrection) {
            current = next
            V2BlindSpotSettings.setCorrection(activity, side, next)
            if (previewSide == side) {
                V2CameraServiceCommands.showBlindSpotPreview(activity, side)
            }
        }
        container.addView(sliderRow("缩放X", 0.5f, 2.0f, current.scaleX) { save(current.copy(scaleX = it)) })
        container.addView(sliderRow("缩放Y", 0.5f, 2.0f, current.scaleY) { save(current.copy(scaleY = it)) })
        container.addView(sliderRow("平移X", -1.0f, 1.0f, current.translateX) { save(current.copy(translateX = it)) })
        container.addView(sliderRow("平移Y", -1.0f, 1.0f, current.translateY) { save(current.copy(translateY = it)) })
        container.addView(sliderRow("旋转", 0f, 360f, current.rotation) { save(current.copy(rotation = it)) })
        container.addView(mirrorRow("水平镜像", current.mirrorH) { save(current.copy(mirrorH = it)) })
        container.addView(mirrorRow("垂直镜像", current.mirrorV) { save(current.copy(mirrorV = it)) })
        return container
    }

    private fun mirrorRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
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
}
