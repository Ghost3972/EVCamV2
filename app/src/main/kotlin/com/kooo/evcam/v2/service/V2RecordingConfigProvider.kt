package com.kooo.evcam.v2.service

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.settings.V2RecordingSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

object V2RecordingConfigProvider {
    fun current(context: Context, screenSize: Size): V2RecordingConfig {
        val recording = V2SettingsRepository.recordingConfig(context)
        val size = V2RecordingSettings.sizeFromValue(recording.resolution) ?: screenSize
        return V2RecordingConfig(
            size = size,
            fps = recording.fps,
            segmentDurationMs = recording.segmentMinutes * 60_000L,
            bitrate = V2RecordingSettings.bitrateForLevel(size, recording.bitrateLevel),
        )
    }
}
