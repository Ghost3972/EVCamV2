package com.kooo.evcam.v2.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
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
            subtitle = "窗口方向可选；左右独立缩放、平移、镜像；点击预览后拖动参数可实时查看效果",
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
        card.addView(windowOrientationRow())
        card.addView(enableRow(paramsContainer))
        card.addView(paramsContainer)
        rebuildParams()
        return card
    }

    private fun windowOrientationRow(): View {
        val labels = listOf("竖向窗口", "横向窗口")
        val selected = if (V2BlindSpotSettings.windowOrientation(activity) == V2BlindSpotSettings.WINDOW_ORIENTATION_LANDSCAPE) 1 else 0
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(activity).apply {
            text = "窗口方向"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dropdown = cards.dropdownField(
            labels = labels,
            selectedIndex = selected,
            onSelected = { },
            canSelect = { position ->
                val orientation = if (position == 1) {
                    V2BlindSpotSettings.WINDOW_ORIENTATION_LANDSCAPE
                } else {
                    V2BlindSpotSettings.WINDOW_ORIENTATION_PORTRAIT
                }
                V2BlindSpotSettings.setWindowOrientation(activity, orientation)
                previewSide?.let { side -> V2CameraServiceCommands.showBlindSpotPreview(activity, side) }
                Toast.makeText(activity, "补盲窗口方向已切换为${labels[position]}", Toast.LENGTH_SHORT).show()
                true
            },
            widthDp = 240,
        )
        row.addView(dropdown, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { dropdown.performClick() }
        return row
    }

    private fun enableRow(paramsContainer: View): View {
        val enableRow = cards.switchRow()
        enableRow.addView(TextView(activity).apply {
            text = "启用画面矫正"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enableSwitch = cards.settingSwitch(V2BlindSpotSettings.isCorrectionEnabled(activity)) { enabled ->
                V2BlindSpotSettings.setCorrectionEnabled(activity, enabled)
                paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
                V2CameraServiceCommands.refreshBlindSpot(activity)
        }
        enableRow.addView(enableSwitch)
        return enableRow
    }

    private fun resetButton(onReset: () -> Unit): View = cards.actionButton("恢复默认参数", {
        V2BlindSpotSettings.resetAllCorrections(activity)
        previewSide?.let { side -> V2CameraServiceCommands.showBlindSpotPreview(activity, side) }
        Toast.makeText(activity, "补盲矫正参数已恢复默认", Toast.LENGTH_SHORT).show()
        onReset()
    }, minWidthDp = 220, minHeightDp = 80).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
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
        titleRow.addView(cards.actionButton("预览", {
            previewSide = side
            V2CameraServiceCommands.showBlindSpotPreview(activity, side)
        }, minWidthDp = 96, minHeightDp = 64), LinearLayout.LayoutParams(dp(96), dp(64)))
        container.addView(titleRow)

        var current = V2BlindSpotSettings.correction(activity, side)
        fun save(next: V2BlindSpotCorrection) {
            current = next
            V2BlindSpotSettings.setCorrection(activity, side, next)
            if (previewSide == side) {
                V2CameraServiceCommands.showBlindSpotPreview(activity, side)
            }
        }
        container.addView(sliderRow(
            "缩放X",
            V2BlindSpotSettings.MIN_CORRECTION_SCALE,
            V2BlindSpotSettings.MAX_CORRECTION_SCALE,
            current.scaleX,
        ) { save(current.copy(scaleX = it)) })
        container.addView(sliderRow(
            "缩放Y",
            V2BlindSpotSettings.MIN_CORRECTION_SCALE,
            V2BlindSpotSettings.MAX_CORRECTION_SCALE,
            current.scaleY,
        ) { save(current.copy(scaleY = it)) })
        container.addView(sliderRow(
            "平移X",
            V2BlindSpotSettings.MIN_CORRECTION_TRANSLATE,
            V2BlindSpotSettings.MAX_CORRECTION_TRANSLATE,
            current.translateX,
        ) { save(current.copy(translateX = it)) })
        container.addView(sliderRow(
            "平移Y",
            V2BlindSpotSettings.MIN_CORRECTION_TRANSLATE,
            V2BlindSpotSettings.MAX_CORRECTION_TRANSLATE,
            current.translateY,
        ) { save(current.copy(translateY = it)) })
        container.addView(sliderRow(
            "旋转",
            V2BlindSpotSettings.MIN_CORRECTION_ROTATION,
            V2BlindSpotSettings.MAX_CORRECTION_ROTATION,
            current.rotation,
        ) { save(current.copy(rotation = it)) })
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
        val toggle = cards.settingSwitch(checked) { enabled -> onChanged(enabled) }
        line.addView(toggle)
        return line
    }

    private fun sliderRow(label: String, min: Float, rangeMax: Float, value: Float, onChanged: (Float) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val valueText = TextView(activity).apply {
            text = "$label ${formatParam(value)}"
            textSize = 16f
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val seekBar = cards.styleSlider(SeekBar(activity).apply {
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
        })
        row.addView(valueText, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(14)
        })
        row.addView(seekBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        return row
    }

    private fun formatParam(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = cards.dp(value)
}
