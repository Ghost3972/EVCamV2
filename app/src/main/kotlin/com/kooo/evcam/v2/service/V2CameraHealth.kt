package com.kooo.evcam.v2.service

import com.kooo.evcam.v2.recording.RecordingMetrics

data class V2CameraHealthSnapshot(
    val cameraAccessAllowed: Boolean,
    val released: Boolean,
    val recording: Boolean,
    val slots: List<V2CameraSlotHealth>,
    val recordingMetrics: RecordingMetrics?,
)

data class V2CameraSlotHealth(
    val index: Int,
    val label: String,
    val cameraId: String,
    val deviceOpen: Boolean,
    val sessionOpen: Boolean,
    val inputReady: Boolean,
    val previewAttached: Boolean,
    val frameSignals: Long,
    val renderedFrames: Long,
    val renderFailures: Long,
    val lastRenderMs: Long,
    val lastError: String,
)
