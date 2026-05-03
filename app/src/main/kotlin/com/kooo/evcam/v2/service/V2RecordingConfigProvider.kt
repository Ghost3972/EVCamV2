package com.kooo.evcam.v2.service

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.settings.V2RecordingSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

object V2RecordingConfigProvider {
    private const val COMPOSITE_COLUMNS = 2
    private const val COMPOSITE_ROWS = 2

    fun current(context: Context, screenSize: Size): V2RecordingConfig {
        val recording = V2SettingsRepository.recordingConfig(context)
        val perCameraSize = V2RecordingSettings.sizeFromValue(recording.resolution) ?: screenSize
        val outputSize = Size(
            perCameraSize.width * COMPOSITE_COLUMNS,
            perCameraSize.height * COMPOSITE_ROWS,
        )
        return V2RecordingConfig(
            size = perCameraSize,
            outputSize = outputSize,
            fps = recording.fps,
            segmentDurationMs = recording.segmentMinutes * 60_000L,
            bitrate = V2RecordingSettings.bitrateForLevel(outputSize, recording.bitrateLevel),
        )
    }
}
