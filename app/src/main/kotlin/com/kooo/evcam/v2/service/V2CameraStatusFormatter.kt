package com.kooo.evcam.v2.service

import android.os.SystemClock
import android.util.Size
import com.kooo.evcam.v2.recording.RecordingMetrics
import java.util.Locale

class V2CameraStatusFormatter(
    private val recordingSize: Size,
    private val recordingFps: Int
) {
    data class SlotStatus(
        val index: Int,
        val label: String,
        val inputSizeLabel: String,
        val frameSignals: Long,
        val renderedFrames: Long,
        val renderFailures: Long,
        val lastPreviewError: String,
        val lastRenderMs: Long
    )

    private data class SlotFpsState(
        var windowStartedMs: Long = SystemClock.elapsedRealtime(),
        var lastSignals: Long = 0L,
        var lastRenders: Long = 0L
    )

    private val slotFpsStates = mutableMapOf<Int, SlotFpsState>()
    private var cachedSlotFpsDebug = "前 -- sig=0.0 view=0.0 0ms  后 -- sig=0.0 view=0.0 0ms\n左 -- sig=0.0 view=0.0 0ms  右 -- sig=0.0 view=0.0 0ms"
    private var lastSlotFpsDebugMs = 0L
    private var lastRecordingFpsDebugMs = 0L
    private var lastRecordingRenderedFrames = 0L
    private var cachedRecordingFps = 0f

    fun status(recording: Boolean, recordingStartedAtMs: Long, metrics: RecordingMetrics?, slots: List<SlotStatus>): String {
        val elapsed = if (recording && recordingStartedAtMs > 0L) formatDuration(SystemClock.elapsedRealtime() - recordingStartedAtMs) else "00:00"
        val segments = if (recording) ((metrics?.segmentIndex ?: 0) + 1).coerceAtLeast(1) else 0
        val line1 = "rec=${if (recording) "ON" else "OFF"} $elapsed 分片=$segments out=${recordingSize.width}x${recordingSize.height}@$recordingFps enc=${recordingFpsDebug(recording, metrics)}"
        return "$line1\n${slotFpsDebug(slots)}"
    }

    fun resetSlot(index: Int, frameSignals: Long, renderedFrames: Long) {
        slotFpsStates[index] = SlotFpsState(
            windowStartedMs = SystemClock.elapsedRealtime(),
            lastSignals = frameSignals,
            lastRenders = renderedFrames
        )
    }

    private fun recordingFpsDebug(recording: Boolean, metrics: RecordingMetrics?): String {
        if (!recording || metrics == null) {
            lastRecordingFpsDebugMs = 0L
            lastRecordingRenderedFrames = 0L
            cachedRecordingFps = 0f
            return "0.0"
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRecordingFpsDebugMs <= 0L) {
            lastRecordingFpsDebugMs = now
            lastRecordingRenderedFrames = metrics.renderedFrames
            return String.format(Locale.US, "%.1f", cachedRecordingFps)
        }
        if (now - lastRecordingFpsDebugMs >= 900L) {
            val elapsedMs = (now - lastRecordingFpsDebugMs).coerceAtLeast(1L)
            cachedRecordingFps = ((metrics.renderedFrames - lastRecordingRenderedFrames).coerceAtLeast(0L) * 1000f / elapsedMs)
            lastRecordingFpsDebugMs = now
            lastRecordingRenderedFrames = metrics.renderedFrames
        }
        return String.format(Locale.US, "%.1f", cachedRecordingFps)
    }

    private fun slotFpsDebug(slots: List<SlotStatus>): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSlotFpsDebugMs < 900L) return cachedSlotFpsDebug
        lastSlotFpsDebugMs = now
        val slotTexts = slots.map { slot ->
            val state = slotFpsStates.getOrPut(slot.index) { SlotFpsState(lastSignals = slot.frameSignals, lastRenders = slot.renderedFrames) }
            val elapsed = (now - state.windowStartedMs).coerceAtLeast(1L)
            val signalFps = ((slot.frameSignals - state.lastSignals) * 1000f / elapsed)
            val renderFps = ((slot.renderedFrames - state.lastRenders) * 1000f / elapsed)
            state.lastSignals = slot.frameSignals
            state.lastRenders = slot.renderedFrames
            state.windowStartedMs = now
            val error = if (slot.renderFailures > 0L && slot.lastPreviewError != "无") " err=${slot.renderFailures}" else ""
            "${slot.label} ${slot.inputSizeLabel} sig=${"%.1f".format(Locale.US, signalFps)} view=${"%.1f".format(Locale.US, renderFps)} ${slot.lastRenderMs}ms$error"
        }
        cachedSlotFpsDebug = "${slotTexts.getOrElse(0) { "前 --" }}  ${slotTexts.getOrElse(1) { "后 --" }}\n${slotTexts.getOrElse(2) { "左 --" }}  ${slotTexts.getOrElse(3) { "右 --" }}"
        return cachedSlotFpsDebug
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
