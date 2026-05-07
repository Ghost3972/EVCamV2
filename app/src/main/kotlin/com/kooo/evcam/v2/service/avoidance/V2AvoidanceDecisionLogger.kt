package com.kooo.evcam.v2.service.avoidance

import com.kooo.evcam.v2.log.V2AppLog

internal class V2AvoidanceDecisionLogger {
    private var lastDecisionLogMs = 0L

    fun log(
        source: String,
        behaviorMask: Int,
        behaviorLabels: String,
        displayOn: Boolean,
        targets: List<String>,
        currentTarget: String?,
        result: Boolean,
        active: Boolean,
        activeTarget: String?,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDecisionLogMs < DECISION_LOG_INTERVAL_MS) return
        lastDecisionLogMs = now
        V2AppLog.i(
            TAG,
            "avoidance decision source=$source result=$result active=$active activeTarget=$activeTarget " +
                "behaviorMask=$behaviorMask behavior=$behaviorLabels " +
                "displayOn=$displayOn targets=${targets.joinToString()} currentTarget=$currentTarget"
        )
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val DECISION_LOG_INTERVAL_MS = 10_000L
    }
}
