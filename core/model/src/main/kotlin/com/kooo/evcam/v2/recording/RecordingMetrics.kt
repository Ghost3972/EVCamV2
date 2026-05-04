package com.kooo.evcam.v2.recording

data class RecordingMetrics(
    var requestedFrames: Long = 0,
    var renderedFrames: Long = 0,
    var encodedSamples: Long = 0,
    var droppedFrames: Long = 0,
    var segmentIndex: Int = 0,
    var segmentSwitchMs: Long = 0,
    var firstSampleLatencyMs: Long = -1,
    var pipeLockAcquireCount: Long = 0,
    var pipeLockWaitTotalMs: Long = 0,
    var pipeLockWaitMaxMs: Long = 0,
    var pipeTryLockSuccessCount: Long = 0,
    var pipeTryLockFailCount: Long = 0,
    var renderCommandDropCount: Long = 0,
    var renderCommandAppliedCount: Long = 0,
    var recordingQueueProducedCount: Long = 0,
    var recordingQueueConsumedCount: Long = 0,
    var recordingQueueDropCount: Long = 0,
    var recordingQueueDepth: Long = 0,
    var recordingQueueMaxDepth: Long = 0,
    var recordingQueueFallbackCount: Long = 0,
    var recordingQueueFboRecreateCount: Long = 0,
    var lastError: String = "无"
)
