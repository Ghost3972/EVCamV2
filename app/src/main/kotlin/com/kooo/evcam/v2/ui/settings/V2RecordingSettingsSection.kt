package com.kooo.evcam.v2.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2RecordingSettings

class V2RecordingSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    fun create(): View {
        val resolutionOptions = V2RecordingSettings.supportedResolutionOptions(activity)
        val bitrateOptions = V2RecordingSettings.bitrateOptionsWithMbps(activity)
        val row = cards.cardContainer()
        val header = cards.cardTexts(
            "录制设置",
            V2RecordingSettings.summary(activity),
            0,
            useWeight = false
        )
        val summaryText = header.getChildAt(1) as TextView
        row.addView(header)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cards.dp(12), 0, 0)
        }
        controls.addView(dropdownCell(
            label = "摄像头分辨率",
            labels = resolutionOptions.map { it.label },
            selectedIndex = resolutionOptions.indexOfFirst { it.value == V2RecordingSettings.resolution(activity) }.coerceAtLeast(0),
            onSelected = { index -> V2RecordingSettings.setResolution(activity, resolutionOptions[index].value); restartNotice(summaryText) }
        ))
        controls.addView(dropdownCell(
            label = "码率",
            labels = bitrateOptions.map { it.label },
            selectedIndex = V2RecordingSettings.bitrateOptions.indexOfFirst { it.value == V2RecordingSettings.bitrateLevel(activity) }.coerceAtLeast(0),
            onSelected = { index -> V2RecordingSettings.setBitrateLevel(activity, V2RecordingSettings.bitrateOptions[index].value); restartNotice(summaryText) }
        ))
        controls.addView(dropdownCell(
            label = "帧率",
            labels = V2RecordingSettings.fpsOptions.map { "${it}fps" },
            selectedIndex = V2RecordingSettings.fpsOptions.indexOf(V2RecordingSettings.fps(activity)).coerceAtLeast(0),
            onSelected = { index -> V2RecordingSettings.setFps(activity, V2RecordingSettings.fpsOptions[index]); restartNotice(summaryText) }
        ))
        controls.addView(dropdownCell(
            label = "分段时长",
            labels = V2RecordingSettings.segmentMinuteOptions.map { "${it}分钟" },
            selectedIndex = V2RecordingSettings.segmentMinuteOptions.indexOf(V2RecordingSettings.segmentMinutes(activity)).coerceAtLeast(0),
            onSelected = { index -> V2RecordingSettings.setSegmentMinutes(activity, V2RecordingSettings.segmentMinuteOptions[index]); restartNotice(summaryText) }
        ))
        row.addView(controls)
        return row
    }

    private fun dropdownCell(label: String, labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit): View {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(cards.dp(4), 0, cards.dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(activity).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dropdown = cards.dropdownField(
            labels = labels,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
        )
        cell.addView(dropdown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = cards.dp(6) })
        cell.setOnClickListener { dropdown.performClick() }
        return cell
    }

    private fun restartNotice(summaryText: TextView) {
        summaryText.text = V2RecordingSettings.summary(activity)
        Toast.makeText(activity, "需重启生效", Toast.LENGTH_SHORT).show()
        V2AppLog.i("V2SettingsActivity", "recording settings changed ${V2RecordingSettings.summary(activity).replace('\n', ' ')}")
    }
}
