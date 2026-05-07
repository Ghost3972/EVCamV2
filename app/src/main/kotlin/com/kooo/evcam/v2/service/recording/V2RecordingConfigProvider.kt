package com.kooo.evcam.v2.service.recording

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.service.V2RecordingConfig
import com.kooo.evcam.v2.settings.V2RecordingSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

object V2RecordingConfigProvider {
    fun current(context: Context, screenSize: Size): V2RecordingConfig {
        val recording = V2SettingsRepository.recordingConfig(context)
        val perCameraSize = V2RecordingSettings.sizeFromValue(recording.resolution) ?: screenSize
        val outputSize = V2RecordingSettings.compositeOutputSize(perCameraSize)
        return V2RecordingConfig(
            size = perCameraSize,
            outputSize = outputSize,
            fps = recording.fps,
            segmentDurationMs = recording.segmentMinutes * 60_000L,
            bitrate = V2RecordingSettings.compositeBitrateForLevel(perCameraSize, recording.bitrateLevel),
        )
    }
}
