package com.kooo.evcam.v2.service

import android.util.Size

data class V2RecordingConfig(
    /** Resolution requested for each camera input stream. */
    val size: Size,
    /** Final encoded composite resolution. */
    val outputSize: Size,
    val fps: Int,
    val segmentDurationMs: Long,
    val bitrate: Int,
)
