package com.kooo.evcam.v2.service

import android.util.Size

data class V2RecordingConfig(
    val size: Size,
    val fps: Int,
    val segmentDurationMs: Long,
    val bitrate: Int,
    val segmentPrecreateEnabled: Boolean,
    val h265Enabled: Boolean,
)
