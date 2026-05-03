package com.kooo.evcam.v2.recording

data class V2NativeRecordingTickEvent(
    val shouldRender: Boolean,
    val dropped: Boolean,
    val segmentDue: Boolean,
    val drainedSamples: Long,
    val nextSegmentIndex: Int,
) {
    companion object {
        const val SHOULD_RENDER: Long = 1L
        const val DROPPED: Long = 2L
        const val SEGMENT_DUE: Long = 4L
        const val DRAINED_SHIFT: Int = 8
        const val DRAINED_MASK: Long = 0x00FF_FFFFL
        const val NEXT_INDEX_SHIFT: Int = 32
        const val ERROR_THREAD_ATTACH: Long = -10L
        const val ERROR_TICK_RENDER_DRAIN: Long = -11L

        fun errorLabel(raw: Long): String = when (raw) {
            ERROR_THREAD_ATTACH -> "thread_attach"
            ERROR_TICK_RENDER_DRAIN -> "tick_render_drain"
            else -> "unknown($raw)"
        }

        fun parse(raw: Long, currentSegmentIndex: Int): V2NativeRecordingTickEvent = V2NativeRecordingTickEvent(
            shouldRender = raw and SHOULD_RENDER != 0L,
            dropped = raw and DROPPED != 0L,
            segmentDue = raw and SEGMENT_DUE != 0L,
            drainedSamples = (raw ushr DRAINED_SHIFT) and DRAINED_MASK,
            nextSegmentIndex = (raw ushr NEXT_INDEX_SHIFT).toInt().coerceAtLeast(currentSegmentIndex + 1),
        )
    }
}
