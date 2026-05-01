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
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2FisheyeSettings
import java.util.Locale

class V2FisheyeSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
    private val onRefreshHomePreservingScroll: () -> Unit
) {
    fun create(): View {
        val row = cards.cardContainer()
        row.addView(cards.cardTexts(
            "鱼眼矫正",
            "四路独立参数；点击“预览”打开对应摄像头悬浮窗，修改 k1/k2/zoom 后实时刷新效果\n${V2FisheyeSettings.paramsSummary(activity)}",
            0,
            useWeight = false
        ))

        val paramsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (V2FisheyeSettings.isEnabled(activity)) View.VISIBLE else View.GONE
        }
        row.addView(enableRow(paramsContainer))
        row.addView(paramsContainer.apply {
            addView(resetButton())
            repeat(4) { index -> addView(paramRow(index)) }
        })
        return row
    }

    private fun enableRow(paramsContainer: View): View {
        val enableRow = cards.switchRow()
        enableRow.addView(TextView(activity).apply {
            text = "启用鱼眼矫正"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enableSwitch = Switch(activity).apply {
            isChecked = V2FisheyeSettings.isEnabled(activity)
            setOnCheckedChangeListener { _, enabled ->
                V2FisheyeSettings.setEnabled(activity, enabled)
                paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
                V2AppLog.i(TAG, "fisheyeCorrection=$enabled")
                V2CameraServiceCommands.refreshFisheye(activity)
                Toast.makeText(activity, if (enabled) "鱼眼矫正已开启" else "鱼眼矫正已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        enableRow.setOnClickListener { enableSwitch.toggle() }
        enableRow.addView(enableSwitch)
        return enableRow
    }

    private fun resetButton(): View = Button(activity).apply {
        text = "恢复默认参数"
        textSize = 14f
        minHeight = dp(44)
        setOnClickListener {
            V2FisheyeSettings.resetAllParams(activity)
            V2CameraServiceCommands.refreshFisheye(activity)
            Toast.makeText(activity, "鱼眼参数已恢复默认", Toast.LENGTH_SHORT).show()
            onRefreshHomePreservingScroll()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun paramRow(index: Int): View {
        val params = V2FisheyeSettings.paramsForIndex(activity, index)
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(activity).apply {
            text = "${params.label} 摄像头"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(Button(activity).apply {
            text = "预览"
            textSize = 14f
            minHeight = dp(40)
            setOnClickListener { V2CameraServiceCommands.showFisheyePreview(activity, index) }
        }, LinearLayout.LayoutParams(dp(86), dp(44)))
        line.addView(title)

        var currentK1 = params.k1
        var currentK2 = params.k2
        var currentZoom = params.zoom
        fun saveAndRefresh() {
            V2FisheyeSettings.setParams(activity, index, currentK1, currentK2, currentZoom)
            V2CameraServiceCommands.refreshFisheye(activity)
            V2AppLog.i(TAG, "fisheye slider index=$index k1=$currentK1 k2=$currentK2 zoom=$currentZoom")
        }

        line.addView(sliderRow("k1", -1.20f, 1.50f, currentK1) { currentK1 = it; saveAndRefresh() })
        line.addView(sliderRow("k2", -0.80f, 0.80f, currentK2) { currentK2 = it; saveAndRefresh() })
        line.addView(sliderRow("zoom", 0.80f, 2.00f, currentZoom) { currentZoom = it; saveAndRefresh() })
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
