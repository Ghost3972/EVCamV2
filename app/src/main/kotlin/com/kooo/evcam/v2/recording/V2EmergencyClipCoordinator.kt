package com.kooo.evcam.v2.recording

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class V2EmergencyClipCoordinator(
    context: Context,
    private val outputDir: File,
    private val segmentDurationMs: Long,
    private val isRecording: () -> Boolean,
    private val stoppedWallClockMs: () -> Long,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private data class Request(
        val startWallClockMs: Long,
        val endWallClockMs: Long,
    )

    private data class ReadyClip(
        val request: Request,
        val exportEndWallClockMs: Long,
        val sources: List<V2EmergencySourceSegment>,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val pendingRequests = mutableListOf<Request>()
    private val finalizedSegments = mutableListOf<V2EmergencySourceSegment>()

    fun request(startWallClockMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val request = Request(startWallClockMs, startWallClockMs + durationMs)
        synchronized(lock) { pendingRequests += request }
        V2AppLog.i(TAG, "emergency clip requested start=$startWallClockMs durationMs=$durationMs")
        tryExportPending()
        return true
    }

    fun onSegmentFinalized(file: File, startWallClockMs: Long, endWallClockMs: Long) {
        if (startWallClockMs <= 0L || endWallClockMs <= startWallClockMs) return
        synchronized(lock) {
            finalizedSegments += V2EmergencySourceSegment(file, startWallClockMs, endWallClockMs)
            val oldestPendingStart = pendingRequests.minOfOrNull { it.startWallClockMs }
                ?: (System.currentTimeMillis() - segmentDurationMs * 2)
            finalizedSegments.removeAll { it.endWallClockMs < oldestPendingStart - segmentDurationMs }
        }
        V2AppLog.d(TAG, "normal segment finalized for emergency clips file=${file.name}")
        tryExportPending()
    }

    fun finishExportsIfStopped() {
        if (isRecording() || stoppedWallClockMs() <= 0L) return
        synchronized(lock) {
            val dropped = pendingRequests.size
            if (dropped > 0) {
                V2AppLog.w(TAG, "drop uncovered emergency clip requests on stop count=$dropped")
                pendingRequests.clear()
            }
        }
        executor.shutdown()
    }

    private fun tryExportPending() {
        val ready = synchronized(lock) {
            val stoppedAt = stoppedWallClockMs()
            val readyRequests = pendingRequests.mapNotNull { request ->
                val exportEnd = if (stoppedAt > 0L && request.endWallClockMs > stoppedAt) stoppedAt else request.endWallClockMs
                if (exportEnd <= request.startWallClockMs) return@mapNotNull null
                if (finalizedSegments.any { it.startWallClockMs <= request.startWallClockMs && it.endWallClockMs > request.startWallClockMs } &&
                    finalizedSegments.any { it.startWallClockMs < exportEnd && it.endWallClockMs >= exportEnd }) {
                    ReadyClip(request, exportEnd, finalizedSegments.filter { it.endWallClockMs > request.startWallClockMs && it.startWallClockMs < exportEnd })
                } else {
                    null
                }
            }
            pendingRequests.removeAll(readyRequests.map { it.request }.toSet())
            readyRequests
        }
        for (clip in ready) {
            executor.execute {
                V2EmergencyClipExtractor.extract(
                    context = appContext,
                    outputDir = outputDir,
                    clipStartWallClockMs = clip.request.startWallClockMs,
                    clipEndWallClockMs = clip.exportEndWallClockMs,
                    sources = clip.sources,
                )
            }
        }
    }

    private companion object {
        private const val TAG = "V2CompositeRecorder"
    }
}
