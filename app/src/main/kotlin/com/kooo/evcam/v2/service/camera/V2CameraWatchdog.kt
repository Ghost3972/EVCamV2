package com.kooo.evcam.v2.service.camera

import android.os.Handler
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraHealthSnapshot
import java.util.Locale

class V2CameraWatchdog(
    private val handler: Handler,
    private val engine: V2CameraEngine,
    private val isDisplayPowerOn: () -> Boolean,
    private val shouldExpectPreviewRendering: (recording: Boolean) -> Boolean,
    private val onRestartRequired: (reason: String) -> Unit,
) {
    private var lastSnapshot: V2CameraHealthSnapshot? = null
    private var lastSnapshotMs = 0L
    private var failureCount = 0
    private var lastResetMs = 0L
    private var lastPerformanceLogMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
                .onFailure { V2AppLog.e(TAG, "watchdog tick failed", it) }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        reset("start", log = false)
        handler.postDelayed(tick, CHECK_INTERVAL_MS)
        V2AppLog.i(TAG, "watchdog started interval=${CHECK_INTERVAL_MS}ms")
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    fun reset(reason: String, log: Boolean = true) {
        lastResetMs = SystemClock.elapsedRealtime()
        failureCount = 0
        lastSnapshot = engine.healthSnapshot()
        lastSnapshotMs = lastResetMs
        lastPerformanceLogMs = 0L
        if (log) V2AppLog.i(TAG, "watchdog reset reason=$reason")
    }

    private fun check() {
        if (!isDisplayPowerOn()) {
            reset("display_off_skip")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val snapshot = engine.healthSnapshot()
        val previous = lastSnapshot
        val previousMs = lastSnapshotMs
        logPerformanceSnapshot(snapshot, previous, (now - previousMs).coerceAtLeast(1L))
        lastSnapshot = snapshot
        lastSnapshotMs = now
        if (now - lastResetMs < GRACE_MS) return

        val issues = detectIssues(snapshot, previous)
        if (issues.isEmpty()) {
            if (failureCount > 0) V2AppLog.i(TAG, "watchdog recovered")
            failureCount = 0
            return
        }

        failureCount += 1
        V2AppLog.w(TAG, "watchdog issue count=$failureCount/$FAILURE_THRESHOLD ${issues.joinToString("; ")}")
        if (failureCount >= FAILURE_THRESHOLD) {
            reset("restart")
            onRestartRequired(issues.joinToString("; "))
        }
    }

    private fun detectIssues(snapshot: V2CameraHealthSnapshot, previous: V2CameraHealthSnapshot?): List<String> {
        val issues = mutableListOf<String>()
        val brokenSlots = snapshot.slots.filter { !it.inputReady || !it.deviceOpen || !it.sessionOpen }
        if (brokenSlots.isNotEmpty()) {
            issues += "camera=${brokenSlots.joinToString { "${it.label}(input=${it.inputReady},dev=${it.deviceOpen},sess=${it.sessionOpen})" }}"
        }

        if (previous != null) {
            if (shouldExpectPreviewRendering(snapshot.recording)) {
                if (!snapshot.compositePreviewAttached && snapshot.slots.none { it.singlePreviewAttached }) {
                    issues += "preview_surface=missing"
                }
                if (snapshot.compositePreviewAttached &&
                    previous.compositePreviewAttached &&
                    snapshot.nativeCompositePreviewRenders <= previous.nativeCompositePreviewRenders
                ) {
                    issues += "composite_preview_stalled(r=${snapshot.nativeCompositePreviewRenders})"
                }

                val unlatchedInputs = snapshot.slots.filter { slot ->
                    slot.requiresNativeInputWatch(snapshot) && slot.nativeInputAttached && !slot.nativeHasLatchedFrame
                }
                if (unlatchedInputs.isNotEmpty()) {
                    issues += "input_unlatched=${unlatchedInputs.joinToString { "${it.label}(gen=${it.nativeFrameGeneration},upd=${it.nativeInputUpdates},dirty=${it.nativeInputDirty})" }}"
                }

                val stalledInputs = snapshot.slots.filter { slot ->
                    val prev = previous.slots.firstOrNull { it.index == slot.index } ?: return@filter false
                    slot.requiresNativeInputWatch(snapshot) &&
                        slot.nativeInputAttached &&
                        prev.nativeInputAttached &&
                        slot.nativeFrameGeneration <= prev.nativeFrameGeneration &&
                        slot.nativeInputUpdates <= prev.nativeInputUpdates
                }
                if (stalledInputs.isNotEmpty()) {
                    issues += "input_stalled=${stalledInputs.joinToString { "${it.label}(gen=${it.nativeFrameGeneration},lat=${it.nativeLatchedGeneration},upd=${it.nativeInputUpdates})" }}"
                }

                val stalledLatches = snapshot.slots.filter { slot ->
                    val prev = previous.slots.firstOrNull { it.index == slot.index } ?: return@filter false
                    slot.requiresNativeInputWatch(snapshot) &&
                        slot.nativeInputAttached &&
                        slot.nativeFrameGeneration > prev.nativeFrameGeneration &&
                        slot.nativeLatchedGeneration <= prev.nativeLatchedGeneration
                }
                if (stalledLatches.isNotEmpty()) {
                    issues += "latch_stalled=${stalledLatches.joinToString { "${it.label}(gen=${it.nativeFrameGeneration},lat=${it.nativeLatchedGeneration},dirty=${it.nativeInputDirty})" }}"
                }
            }

            if (snapshot.recording) {
                val metrics = snapshot.recordingMetrics
                val prevMetrics = previous.recordingMetrics
                when {
                    metrics == null -> issues += "recording=metrics_missing"
                    prevMetrics != null && metrics.requestedFrames <= prevMetrics.requestedFrames -> issues += "recording=request_stalled(${metrics.requestedFrames})"
                    prevMetrics != null && metrics.renderedFrames <= prevMetrics.renderedFrames -> issues += "recording=render_stalled(${metrics.renderedFrames})"
                    prevMetrics != null && metrics.encodedSamples <= prevMetrics.encodedSamples -> issues += "recording=encode_stalled(${metrics.encodedSamples})"
                    metrics.lastError != "无" -> issues += "recording=err:${metrics.lastError}"
                }
            }
        }
        return issues
    }

    private fun logPerformanceSnapshot(
        snapshot: V2CameraHealthSnapshot,
        previous: V2CameraHealthSnapshot?,
        deltaMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (failureCount == 0 && now - lastPerformanceLogMs < PERFORMANCE_LOG_INTERVAL_MS) return
        lastPerformanceLogMs = now
        val slotText = snapshot.slots.joinToString(prefix = "[", postfix = "]", separator = " ") { slot ->
            val prev = previous?.slots?.firstOrNull { it.index == slot.index }
            val signalFps = ratePerSecond(slot.frameSignals - (prev?.frameSignals ?: slot.frameSignals), deltaMs)
            "${slot.label}{open=${slot.deviceOpen && slot.sessionOpen} single=${slot.singlePreviewAttached} sig=${formatRate(signalFps)} fail=${slot.renderFailures} in=${slot.nativeInputAttached} latch=${slot.nativeHasLatchedFrame} dirty=${slot.nativeInputDirty} gen=${slot.nativeFrameGeneration}/${slot.nativeLatchedGeneration}/${slot.nativePreviewGeneration}/${slot.nativeEncoderGeneration} upd=${slot.nativeInputUpdates} last=${slot.lastRenderMs}ms err=${slot.lastError}}"
        }

        val metrics = snapshot.recordingMetrics
        val prevMetrics = previous?.recordingMetrics
        val recordingText = if (snapshot.recording && metrics != null) {
            val requestFps = ratePerSecond(metrics.requestedFrames - (prevMetrics?.requestedFrames ?: metrics.requestedFrames), deltaMs)
            val renderFps = ratePerSecond(metrics.renderedFrames - (prevMetrics?.renderedFrames ?: metrics.renderedFrames), deltaMs)
            val encodeFps = ratePerSecond(metrics.encodedSamples - (prevMetrics?.encodedSamples ?: metrics.encodedSamples), deltaMs)
            val dropDelta = (metrics.droppedFrames - (prevMetrics?.droppedFrames ?: metrics.droppedFrames)).coerceAtLeast(0L)
            val lockFailDelta = (metrics.pipeTryLockFailCount - (prevMetrics?.pipeTryLockFailCount ?: metrics.pipeTryLockFailCount)).coerceAtLeast(0L)
            val commandDropDelta = (metrics.renderCommandDropCount - (prevMetrics?.renderCommandDropCount ?: metrics.renderCommandDropCount)).coerceAtLeast(0L)
            val queueProducedDelta = (metrics.recordingQueueProducedCount - (prevMetrics?.recordingQueueProducedCount ?: metrics.recordingQueueProducedCount)).coerceAtLeast(0L)
            val queueConsumedDelta = (metrics.recordingQueueConsumedCount - (prevMetrics?.recordingQueueConsumedCount ?: metrics.recordingQueueConsumedCount)).coerceAtLeast(0L)
            val queueDropDelta = (metrics.recordingQueueDropCount - (prevMetrics?.recordingQueueDropCount ?: metrics.recordingQueueDropCount)).coerceAtLeast(0L)
            "rec=ON req=${formatRate(requestFps)} render=${formatRate(renderFps)} enc=${formatRate(encodeFps)} drop=$dropDelta totalDrop=${metrics.droppedFrames} seg=${metrics.segmentIndex} q=${metrics.recordingQueueDepth}/${metrics.recordingQueueMaxDepth} qProd=$queueProducedDelta qUse=$queueConsumedDelta qDrop=$queueDropDelta qFallback=${metrics.recordingQueueFallbackCount} lockMax=${metrics.pipeLockWaitMaxMs}ms lockFail=$lockFailDelta cmdDrop=$commandDropDelta switch=${metrics.segmentSwitchMs}ms first=${metrics.firstSampleLatencyMs}ms err=${metrics.lastError}"
        } else {
            "rec=OFF"
        }

        V2AppLog.i(
            "V2Perf",
            "dt=${deltaMs}ms display=${isDisplayPowerOn()} failures=$failureCount composite=${snapshot.compositePreviewAttached}/${snapshot.nativeCompositePreviewRenders} $recordingText slots=$slotText"
        )
    }

    private fun com.kooo.evcam.v2.service.V2CameraSlotHealth.requiresNativeInputWatch(
        snapshot: V2CameraHealthSnapshot,
    ): Boolean = singlePreviewAttached || snapshot.compositePreviewAttached

    private fun ratePerSecond(delta: Long, deltaMs: Long): Float = delta.coerceAtLeast(0L) * 1000f / deltaMs.coerceAtLeast(1L)

    private fun formatRate(value: Float): String = String.format(Locale.US, "%.1f", value)

    private companion object {
        private const val TAG = "V2CameraService"
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val PERFORMANCE_LOG_INTERVAL_MS = 60_000L
        private const val GRACE_MS = 20_000L
        private const val FAILURE_THRESHOLD = 2
    }
}
