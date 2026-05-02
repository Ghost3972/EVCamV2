package com.kooo.evcam.v2.settings

import android.content.Context

object V2SettingsFormatter {
    fun recordingSummary(context: Context): String {
        val snapshot = V2SettingsRepository.currentSnapshot(context).recording
        val res = labelFor(V2RecordingSettings.supportedResolutionOptions(context), snapshot.resolution)
        val br = labelFor(V2RecordingSettings.bitrateOptionsWithMbps(context), snapshot.bitrateLevel)
        val precreate = if (snapshot.segmentPrecreateEnabled) "开" else "关"
        val codec = if (snapshot.h265Enabled) "H.265" else "H.264"
        return "分辨率：$res；编码：$codec；码率：$br；帧率：${snapshot.fps}fps；分段：${snapshot.segmentMinutes}分钟；预创建：$precreate\n更改后重启应用/服务生效"
    }

    fun fisheyeParamsSummary(context: Context): String {
        val fisheye = V2SettingsRepository.currentSnapshot(context).fisheye
        return V2FisheyeParams.summary(fisheye.params)
    }

    fun avoidanceTargetsSummary(context: Context): String {
        val targets = V2SettingsRepository.avoidanceConfig(context).targets
        return V2AvoidanceSettings.defaultTargets
            .filter { it.value in targets }
            .joinToString("、") { it.label }
            .ifBlank { "未选择窗口" }
    }

    private fun labelFor(options: List<V2RecordingSettings.Option>, value: String): String =
        options.firstOrNull { it.value == value }?.label ?: value
}
