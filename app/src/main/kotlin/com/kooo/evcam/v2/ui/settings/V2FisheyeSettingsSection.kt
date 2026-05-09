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
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2FisheyeParams
import java.util.Locale

class V2FisheyeSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private val controller = V2FisheyeSettingsController(activity)

    fun create(): View {
        val row = cards.cardContainer()
        val header = cards.cardTexts(
            "鱼眼矫正",
            controller.subtitle(),
            0,
            useWeight = false
        )
        val summaryText = header.getChildAt(1) as TextView
        row.addView(header)

        val previewContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (controller.isPreviewEnabled()) View.VISIBLE else View.GONE
        }
        val blindSpotContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (controller.isBlindSpotEnabled()) View.VISIBLE else View.GONE
        }

        fun updateSummary() {
            summaryText.text = controller.subtitle()
        }

        fun rebuildPreviewParams() {
            previewContainer.removeAllViews()
            previewContainer.addView(importPreviewAvmButton { rebuildPreviewParams() })
            previewContainer.addView(resetPreviewButton { rebuildPreviewParams() })
            controller.previewIndices.forEach { index -> previewContainer.addView(previewParamRow(index) { updateSummary() }) }
            updateSummary()
        }

        fun rebuildBlindSpotParams() {
            blindSpotContainer.removeAllViews()
            blindSpotContainer.addView(importBlindSpotAvmButton { rebuildBlindSpotParams() })
            blindSpotContainer.addView(resetBlindSpotButton { rebuildBlindSpotParams() })
            controller.blindSpotIndices.forEach { index -> blindSpotContainer.addView(blindSpotParamRow(index) { updateSummary() }) }
            updateSummary()
        }

        row.addView(enableRow(
            label = "启用预览/录制鱼眼矫正",
            checked = controller.isPreviewEnabled(),
            paramsContainer = previewContainer,
            onEnabled = { enabled -> controller.setPreviewEnabled(enabled) },
            toastText = { enabled -> if (enabled) "预览/录制鱼眼已开启" else "预览/录制鱼眼已关闭" },
        ))
        row.addView(previewContainer)
        row.addView(sectionTitle("补盲独立鱼眼"))
        row.addView(enableRow(
            label = "启用补盲鱼眼矫正",
            checked = controller.isBlindSpotEnabled(),
            paramsContainer = blindSpotContainer,
            onEnabled = { enabled -> controller.setBlindSpotEnabled(enabled) },
            toastText = { enabled -> if (enabled) "补盲鱼眼已开启" else "补盲鱼眼已关闭" },
        ))
        row.addView(blindSpotContainer)
        rebuildPreviewParams()
        rebuildBlindSpotParams()
        return row
    }

    private fun sectionTitle(textValue: String): View = TextView(activity).apply {
        text = textValue
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun enableRow(
        label: String,
        checked: Boolean,
        paramsContainer: View,
        onEnabled: (Boolean) -> Unit,
        toastText: (Boolean) -> String,
    ): View {
        val enableRow = cards.switchRow()
        enableRow.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enableSwitch = cards.settingSwitch(checked) { enabled ->
            onEnabled(enabled)
            paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
            Toast.makeText(activity, toastText(enabled), Toast.LENGTH_SHORT).show()
        }
        enableRow.addView(enableSwitch)
        return enableRow
    }

    private fun resetPreviewButton(onReset: () -> Unit): View = actionButton("恢复预览/录制默认参数", {
        controller.resetPreviewParams()
        Toast.makeText(activity, "预览/录制鱼眼参数已恢复默认", Toast.LENGTH_SHORT).show()
        onReset()
    })

    private fun importPreviewAvmButton(onImport: () -> Unit): View = actionButton("导入预览/录制 AVM 960 内参", {
        controller.importPreviewAvm960Params()
        Toast.makeText(activity, "预览/录制 AVM 960 内参已导入", Toast.LENGTH_SHORT).show()
        onImport()
    })

    private fun resetBlindSpotButton(onReset: () -> Unit): View = actionButton("恢复补盲鱼眼默认参数", {
        controller.resetBlindSpotParams()
        Toast.makeText(activity, "补盲鱼眼参数已恢复默认", Toast.LENGTH_SHORT).show()
        onReset()
    })

    private fun importBlindSpotAvmButton(onImport: () -> Unit): View = actionButton("导入补盲 AVM 960 内参", {
        controller.importBlindSpotAvm960Params()
        Toast.makeText(activity, "补盲 AVM 960 内参已导入", Toast.LENGTH_SHORT).show()
        onImport()
    })

    private fun actionButton(label: String, onClick: () -> Unit): View =
        cards.actionButton(label, onClick, minWidthDp = 220, minHeightDp = 80).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
        }

    private fun previewParamRow(index: Int, onChanged: () -> Unit): View {
        val params = controller.previewParams(index)
        return paramRow(
            params = params,
            onPreview = { controller.showPreview(index) },
            onSave = { updated -> controller.savePreviewParams(index, updated) },
            onChanged = onChanged,
            logPrefix = "fisheye",
        )
    }

    private fun blindSpotParamRow(index: Int, onChanged: () -> Unit): View {
        val params = controller.blindSpotParams(index)
        return paramRow(
            params = params,
            onPreview = { controller.showBlindSpotPreview(index) },
            onSave = { updated -> controller.saveBlindSpotParams(index, updated) },
            onChanged = onChanged,
            logPrefix = "blindSpotFisheye",
        )
    }

    private fun paramRow(
        params: V2FisheyeParams,
        onPreview: () -> Unit,
        onSave: (V2FisheyeParams) -> Unit,
        onChanged: () -> Unit,
        logPrefix: String,
    ): View {
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
        title.addView(cards.actionButton("预览", onPreview, minWidthDp = 96, minHeightDp = 64), LinearLayout.LayoutParams(dp(96), dp(64)))
        line.addView(title)

        var currentK1 = params.k1
        var currentK2 = params.k2
        var currentK3 = params.k3
        var currentK4 = params.k4
        var currentZoom = params.zoom
        var currentCenterX = params.centerX
        var currentCenterY = params.centerY
        fun saveAndRefresh() {
            val updated = params.copy(
                k1 = currentK1,
                k2 = currentK2,
                k3 = currentK3,
                k4 = currentK4,
                zoom = currentZoom,
                centerX = currentCenterX,
                centerY = currentCenterY,
            )
            onSave(updated)
            V2AppLog.i(TAG, "$logPrefix slider label=${params.label} k1=$currentK1 k2=$currentK2 k3=$currentK3 k4=$currentK4 zoom=$currentZoom center=$currentCenterX,$currentCenterY")
            onChanged()
        }

        line.addView(sliderRow("k1", -1.20f, 1.50f, currentK1) { currentK1 = it; saveAndRefresh() })
        line.addView(sliderRow("k2", -0.80f, 0.80f, currentK2) { currentK2 = it; saveAndRefresh() })
        line.addView(sliderRow("k3", -0.80f, 0.80f, currentK3) { currentK3 = it; saveAndRefresh() })
        line.addView(sliderRow("k4", -0.80f, 0.80f, currentK4) { currentK4 = it; saveAndRefresh() })
        line.addView(sliderRow("zoom", 0.80f, 2.00f, currentZoom) { currentZoom = it; saveAndRefresh() })
        line.addView(sliderRow("centerX", 0.35f, 0.65f, currentCenterX) { currentCenterX = it; saveAndRefresh() })
        line.addView(sliderRow("centerY", 0.35f, 0.65f, currentCenterY) { currentCenterY = it; saveAndRefresh() })
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

    private companion object {
        const val TAG = "V2SettingsActivity"
    }
}
