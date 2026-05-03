package com.kooo.evcam.v2.service

import android.util.Size

data class V2RecordingConfig(
    /** Resolution requested for each camera tile in the 2x2 composite. */
    val size: Size,
    /** Final encoded 2x2 composite resolution. */
    val outputSize: Size,
    val fps: Int,
    val segmentDurationMs: Long,
    val bitrate: Int,
)
