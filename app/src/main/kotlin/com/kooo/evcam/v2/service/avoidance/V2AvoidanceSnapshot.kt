package com.kooo.evcam.v2.service.avoidance

internal data class V2AvoidanceSnapshot(
    val behaviorMask: Int,
    val wasRecording: Boolean,
    val wasUiVisible: Boolean,
)
