package com.kooo.evcam.v2.service.avoidance

import com.kooo.evcam.v2.settings.V2AvoidanceBehaviors

internal data class V2AvoidanceSnapshot(
    val behaviorMask: Int,
    val wasRecording: Boolean,
    val wasUiVisible: Boolean,
) {
    val exitsForeground: Boolean
        get() = behaviorMask and V2AvoidanceBehaviors.EXIT_FOREGROUND != 0
}
