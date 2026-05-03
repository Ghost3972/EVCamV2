package com.kooo.evcam.v2.recording

interface V2RecordingPipeline {
    fun start(): Boolean
    fun stop()
    fun stopBlockingForRelease(timeoutMs: Long = 2_000L)
    fun requestEmergencyClip(startWallClockMs: Long, durationMs: Long): Boolean
    fun metricsSnapshot(): RecordingMetrics
}
