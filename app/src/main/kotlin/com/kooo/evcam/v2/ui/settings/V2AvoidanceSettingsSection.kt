package com.kooo.evcam.v2.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2AvoidanceSettings
import com.kooo.evcam.v2.settings.V2SettingsCategory

class V2AvoidanceSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    fun create(): View {
        val row = cards.cardContainer()
        row.addView(cards.cardTexts(
            "泊车/全景避让",
            "检测到配置窗口在前台时执行；目标退出后恢复进入前的前台、预览、录制状态",
            0,
            useWeight = false
        ))
        row.addView(targetsRow())
        row.addView(behaviorsRow())
        return row
    }

    private fun targetsRow(): View = labeledHorizontalOptionsRow("App/窗口") {
        V2AvoidanceSettings.defaultTargets.forEach { target ->
            addView(CheckBox(activity).apply {
                text = targetLabel(target)
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                isChecked = V2AvoidanceSettings.isTargetEnabled(activity, target)
                setOnCheckedChangeListener { _, enabled ->
                    V2AvoidanceSettings.setTargetEnabled(activity, target, enabled)
                    V2CameraServiceCommands.notifySettingsChangedIfRunning(activity, V2SettingsCategory.AVOIDANCE)
                    V2AppLog.i("V2SettingsActivity", "avoidance target changed ${target.value}=$enabled")
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = cards.dp(10)
            })
        }
    }

    private fun behaviorsRow(): View = labeledHorizontalOptionsRow("避让行为") {
        addView(compactSwitch("退出前台", V2AvoidanceSettings.BEHAVIOR_EXIT_FOREGROUND))
        addView(compactSwitch("停止录制", V2AvoidanceSettings.BEHAVIOR_STOP_RECORDING))
        addView(compactSwitch("补盲避让", V2AvoidanceSettings.BEHAVIOR_HIDE_BLIND_SPOT))
    }

    private fun labeledHorizontalOptionsRow(label: String, buildOptions: LinearLayout.() -> Unit): View {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, cards.dp(8), 0, 0)
        }
        line.addView(TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(cards.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        val options = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buildOptions()
        }
        line.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(options)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return line
    }

    private fun compactSwitch(label: String, behavior: Int): View {
        val option = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, cards.dp(12), 0)
        }
        option.addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        val switch = Switch(activity).apply {
            isChecked = V2AvoidanceSettings.isBehaviorEnabled(activity, behavior)
            setOnCheckedChangeListener { _, enabled ->
                V2AvoidanceSettings.setBehaviorEnabled(activity, behavior, enabled)
                V2CameraServiceCommands.notifySettingsChangedIfRunning(activity, V2SettingsCategory.AVOIDANCE)
                V2AppLog.i("V2SettingsActivity", "avoidance behavior changed $label=$enabled mask=${V2AvoidanceSettings.behaviorMask(activity)}")
            }
        }
        option.setOnClickListener { switch.toggle() }
        option.addView(switch)
        return option
    }

    private fun targetLabel(target: V2AvoidanceSettings.AvoidanceTarget): String = when (target.label) {
        "全景全屏" -> "全景全屏"
        "全景小窗" -> "全景小窗"
        "泊车窗口" -> "泊车窗口"
        "记忆泊车" -> "记忆泊车"
        else -> target.label
    }
}
