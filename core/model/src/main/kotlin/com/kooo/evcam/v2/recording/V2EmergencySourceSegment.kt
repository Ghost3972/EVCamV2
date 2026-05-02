package com.kooo.evcam.v2.recording

import java.io.File

data class V2EmergencySourceSegment(
    val file: File,
    val startWallClockMs: Long,
    val endWallClockMs: Long,
)
