package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2AvoidanceSettings {
    const val BEHAVIOR_EXIT_FOREGROUND = V2AvoidanceBehaviors.EXIT_FOREGROUND
    const val BEHAVIOR_STOP_RECORDING = V2AvoidanceBehaviors.STOP_RECORDING
    const val BEHAVIOR_HIDE_BLIND_SPOT = V2AvoidanceBehaviors.HIDE_BLIND_SPOT
    private const val LEGACY_BEHAVIOR_STOP_PREVIEW = 1 shl 2
    private const val KNOWN_BEHAVIOR_MASK = V2AvoidanceBehaviors.KNOWN_MASK

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

    fun behaviorLabels(mask: Int): String = V2AvoidanceBehaviors.labels(mask)

    fun targetsSummary(context: Context): String = V2SettingsFormatter.avoidanceTargetsSummary(context)

    private fun targetKey(target: AvoidanceTarget): String = KEY_TARGET_PREFIX + target.value

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
