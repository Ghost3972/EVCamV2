package com.kooo.evcam.v2.service

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.settings.V2RecordingSettings

data class V2RecordingConfig(
    val size: Size,
    val fps: Int,
    val segmentDurationMs: Long,
    val bitrate: Int,
)

object V2RecordingConfigProvider {
    fun current(context: Context, screenSize: Size): V2RecordingConfig {
        val size = V2RecordingSettings.recordingSize(context, screenSize)
        return V2RecordingConfig(
            size = size,
            fps = V2RecordingSettings.fps(context),
            segmentDurationMs = V2RecordingSettings.segmentDurationMs(context),
            bitrate = V2RecordingSettings.bitrate(context, size),
        )
    }
}
