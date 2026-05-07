package com.kooo.evcam.v2.settings

import android.content.Context

object V2SettingsFormatter {
    fun recordingSummary(context: Context): String {
        val snapshot = V2SettingsRepository.currentSnapshot(context).recording
        val res = labelFor(V2RecordingSettings.supportedResolutionOptions(context), snapshot.resolution)
        val screen = V2RecordingSettings.screenSize(context)
        val cameraSize = V2RecordingSettings.sizeFromValue(snapshot.resolution) ?: V2RecordingSettings.recordingSize(context, screen)
        val output = V2RecordingSettings.compositeOutputSize(cameraSize)
        val br = labelFor(V2RecordingSettings.bitrateOptionsWithMbps(context), snapshot.bitrateLevel)
        return "摄像头：$res；合成：${output.width}×${output.height}；编码：H.264；码率：$br；帧率：${snapshot.fps}fps；分段：${snapshot.segmentMinutes}分钟\n更改后重启应用/服务生效"
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
