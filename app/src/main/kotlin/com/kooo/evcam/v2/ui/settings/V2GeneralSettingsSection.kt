package com.kooo.evcam.v2.ui.settings

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.keepalive.V2KeepAliveStatus
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.settings.V2VehicleModelSettings

class V2GeneralSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private val appUpdates = V2AppUpdateSettingsCoordinator(activity, cards)

    fun versionCard(): View = cards.entryCard(
        title = "版本信息",
        subtitle = "EVCam V2\n版本：${appUpdates.versionName()}\n包名：${activity.packageName}",
        buttonText = "检查 →",
        onClick = { appUpdates.checkUpdate() }
    )

    fun keepAliveStatusCard(): View {
        val row = cards.cardRow()
        val texts = cards.cardTexts("保活状态", V2KeepAliveStatus.summary(activity), 0)
        val summaryText = texts.getChildAt(1) as TextView
        row.addView(texts)
        row.addView(Button(activity).apply {
            text = "刷新 →"
            textSize = 16f
            minHeight = dp(48)
            setTextColor(ContextCompat.getColor(activity, R.color.button_text))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.button_background))
            setOnClickListener { summaryText.text = V2KeepAliveStatus.summary(activity) }
        })
        return row
    }

    fun logExportCard(): View = cards.entryCard(
        title = "保存日志",
        subtitle = "保存本次运行日志，路径沿用旧版 EVCam_Log 目录设计",
        buttonText = "保存 →",
        onClick = { saveLogs() }
    )

    fun vehicleModelCard(): View {
        val models = V2VehicleModelSettings.models
        val currentIndex = models.indexOfFirst { it.id == V2VehicleModelSettings.getModelId(activity) }.coerceAtLeast(0)
        val row = cards.cardRow()
        val texts = cards.cardTexts(
            "车型配置",
            vehicleModelSubtitle()
        )
        val summaryText = texts.getChildAt(1) as TextView

        var initialized = false
        val spinner = Spinner(activity).apply {
            adapter = vehicleModelAdapter(models.map { it.label })
            setSelection(currentIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) {
                        initialized = true
                        return
                    }
                    V2VehicleModelSettings.setModelId(activity, models[position].id)
                    summaryText.text = vehicleModelSubtitle()
                    V2AppLog.i(TAG, "vehicle model changed to ${models[position].label} ${V2VehicleModelSettings.mappingSummary(activity).replace('\n', ' ')}")
                    Toast.makeText(activity, "需重启生效", Toast.LENGTH_SHORT).show()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.setOnClickListener { spinner.performClick() }
        row.addView(texts)
        row.addView(spinner, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun startupSwitchCard(): View = cards.switchCard(
        title = "开机自启动",
        subtitle = "车机开机后自动启动 EVCam V2",
        checked = V2StartupSettings.isAutoStartOnBoot(activity),
        onCheckedChange = { enabled ->
            V2StartupSettings.setAutoStartOnBoot(activity, enabled)
            V2AppLog.i(TAG, "autoStartOnBoot=$enabled")
        }
    )

    fun recordingSwitchCard(): View = cards.switchCard(
        title = "自动录制",
        subtitle = "软件启动后立即自动开始录制；开机自启动时同样生效",
        checked = V2StartupSettings.isAutoStartRecording(activity),
        onCheckedChange = { enabled ->
            V2StartupSettings.setAutoStartRecording(activity, enabled)
            V2AppLog.i(TAG, "autoStartRecording=$enabled")
        }
    )

    private fun saveLogs() {
        V2AppLog.i(TAG, "manual log export requested")
        val file = V2AppLog.exportCurrentLogs(activity)
        if (file != null) {
            Toast.makeText(activity, "日志已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
            V2AppLog.i(TAG, "manual log exported: ${file.absolutePath}")
        } else {
            Toast.makeText(activity, "暂无日志可保存", Toast.LENGTH_SHORT).show()
            V2AppLog.w(TAG, "manual log export skipped: empty buffer")
        }
    }

    private fun vehicleModelAdapter(labels: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getView(position, convertView, parent) as TextView, dropdown = false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = styledText(super.getDropDownView(position, convertView, parent) as TextView, dropdown = true)

        private fun styledText(view: TextView, dropdown: Boolean): TextView = view.apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(activity, if (dropdown) R.color.card_background else R.color.input_background))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun vehicleModelSubtitle(): String =
        V2VehicleModelSettings.mappingSummary(activity) + "\n使用当前预览布局，仅切换前后左右摄像头映射；更改后重启应用生效"

    private fun dp(value: Int): Int = cards.dp(value)

    private companion object {
        const val TAG = "V2SettingsActivity"
    }
}
