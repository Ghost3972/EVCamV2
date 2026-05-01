package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2AvoidanceSettings {
    const val BEHAVIOR_EXIT_FOREGROUND = 1 shl 0
    const val BEHAVIOR_STOP_RECORDING = 1 shl 1
    const val BEHAVIOR_HIDE_BLIND_SPOT = 1 shl 3
    private const val LEGACY_BEHAVIOR_STOP_PREVIEW = 1 shl 2
    private const val KNOWN_BEHAVIOR_MASK = BEHAVIOR_EXIT_FOREGROUND or BEHAVIOR_STOP_RECORDING or BEHAVIOR_HIDE_BLIND_SPOT

    private const val PREFS_NAME = "evcam_v2_avoidance_settings"
    private const val KEY_BEHAVIOR_MASK = "behavior_mask"
    private const val KEY_TARGET_PREFIX = "target_enabled_"

    val defaultTargets = listOf(
        AvoidanceTarget("com.geely.avm_app.MainActivity", "全景全屏"),
        AvoidanceTarget("com.geely.avm_app.AvmWindowActivity", "全景小窗"),
        AvoidanceTarget("com.geely.parking.parking.ParkingActivity", "泊车窗口"),
        AvoidanceTarget("com.geely.parking.BlankHpaActivity", "记忆泊车")
    )

    data class AvoidanceTarget(val value: String, val label: String)

    fun behaviorMask(context: Context): Int {
        val raw = prefs(context).getInt(KEY_BEHAVIOR_MASK, 0)
        val sanitized = raw and KNOWN_BEHAVIOR_MASK
        if (raw != sanitized) {
            prefs(context).edit().putInt(KEY_BEHAVIOR_MASK, sanitized).apply()
            if (raw and LEGACY_BEHAVIOR_STOP_PREVIEW != 0) {
                V2AppLog.i("V2AvoidanceSettings", "removed legacy stop-preview behavior raw=$raw sanitized=$sanitized")
            }
        }
        return sanitized
    }

    fun setBehaviorEnabled(context: Context, behavior: Int, enabled: Boolean) {
        val current = behaviorMask(context)
        val next = if (enabled) current or behavior else current and behavior.inv()
        prefs(context).edit().putInt(KEY_BEHAVIOR_MASK, next).apply()
        V2AppLog.i("V2AvoidanceSettings", "behaviorMask=$next labels=${behaviorLabels(next)}")
    }

    fun isBehaviorEnabled(context: Context, behavior: Int): Boolean = behaviorMask(context) and behavior != 0

    fun isTargetEnabled(context: Context, target: AvoidanceTarget): Boolean =
        prefs(context).getBoolean(targetKey(target), true)

    fun setTargetEnabled(context: Context, target: AvoidanceTarget, enabled: Boolean) {
        prefs(context).edit().putBoolean(targetKey(target), enabled).apply()
        V2AppLog.i("V2AvoidanceSettings", "target ${target.label} enabled=$enabled value=${target.value}")
    }

    fun targetValues(context: Context): List<String> = defaultTargets
        .filter { isTargetEnabled(context, it) }
        .map { it.value }

    fun behaviorLabels(mask: Int): String = buildList {
        if (mask and BEHAVIOR_EXIT_FOREGROUND != 0) add("退出前台")
        if (mask and BEHAVIOR_STOP_RECORDING != 0) add("停止录制")
        if (mask and BEHAVIOR_HIDE_BLIND_SPOT != 0) add("补盲避让")
    }.ifEmpty { listOf("不避让") }.joinToString("/")

    fun targetsSummary(context: Context): String = defaultTargets
        .filter { isTargetEnabled(context, it) }
        .joinToString("、") { it.shortLabel }
        .ifBlank { "未选择窗口" }

    private val AvoidanceTarget.shortLabel: String
        get() = when (label) {
            "全景全屏" -> "全景全屏"
            "全景小窗" -> "全景小窗"
            "泊车窗口" -> "泊车窗口"
            "记忆泊车" -> "记忆泊车"
            else -> label
        }

    private fun targetKey(target: AvoidanceTarget): String = KEY_TARGET_PREFIX + target.value

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
