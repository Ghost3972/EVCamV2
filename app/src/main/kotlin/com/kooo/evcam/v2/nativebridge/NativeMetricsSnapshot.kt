package com.kooo.evcam.v2.nativebridge

internal object NativeMetricsSnapshot {
    const val RECORDING_REQUESTED_FRAMES = 5
    const val RECORDING_RENDERED_FRAMES = 6
    const val RECORDING_DROPPED_FRAMES = 7
    const val RECORDING_SEGMENT_INDEX = 8
    const val RECORDING_ENCODED_SAMPLES = 17

    const val SLOT_BASE = 20
    const val SLOT_STRIDE = 7
    const val SLOT_FRAME_SIGNALS = 0
    const val SLOT_PREVIEW_RENDERS = 5
    const val SLOT_PREVIEW_DROPS = 6

    const val FINALIZE_QUEUE_DEPTH = 56
    const val FINALIZE_QUEUE_ACTIVE = 57
    const val FINALIZE_QUEUE_RUNNING = 58
    const val FINALIZE_LAST_AVAILABLE_BYTES = 59
    const val FINALIZE_SUBMITTED_COUNT = 60
    const val FINALIZE_COMPLETED_COUNT = 61
    const val FINALIZE_FALLBACK_COUNT = 62
    const val FINALIZE_ACCEPTING = 63

    const val PIPE_LOCK_ACQUIRE_COUNT = 64
    const val PIPE_LOCK_WAIT_TOTAL_MS = 65
    const val PIPE_LOCK_WAIT_MAX_MS = 66
    const val PIPE_TRY_LOCK_SUCCESS_COUNT = 67
    const val PIPE_TRY_LOCK_FAIL_COUNT = 68
    const val RENDER_COMMAND_DROP_COUNT = 69
    const val RENDER_COMMAND_APPLIED_COUNT = 70
    const val PREVIEW_WORKER_NEXT_DEADLINE_MS = 71
    const val RECORDING_QUEUE_PRODUCED_COUNT = 72
    const val RECORDING_QUEUE_CONSUMED_COUNT = 73
    const val RECORDING_QUEUE_DROP_COUNT = 74
    const val RECORDING_QUEUE_DEPTH = 75
    const val RECORDING_QUEUE_MAX_DEPTH = 76
    const val RECORDING_QUEUE_FALLBACK_COUNT = 77
    const val RECORDING_QUEUE_FBO_RECREATE_COUNT = 78
    const val RECORDING_QUEUE_NEXT_CAPTURE_MS = 79

    fun slotBase(index: Int): Int = SLOT_BASE + index * SLOT_STRIDE
}
