package com.kooo.evcam.v2.recording

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackCacheEvents
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class V2EmergencyClipCoordinator(
    context: Context,
    private val outputDir: File,
    private val isRecording: () -> Boolean,
    private val stoppedWallClockMs: () -> Long,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val appContext = context.applicationContext

    fun request(startWallClockMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (!GlesNative.nativeEmergencyRequest(startWallClockMs, startWallClockMs + durationMs)) return false
        V2AppLog.i(TAG, "emergency clip requested start=$startWallClockMs durationMs=$durationMs")
        return true
    }

    fun finishExportsIfStopped() {
        if (isRecording() || stoppedWallClockMs() <= 0L) return
        runCatching {
            executor.submit { drainPending(stoppedWallClockMs()) }.get(2_500L, TimeUnit.MILLISECONDS)
        }.onFailure { V2AppLog.w(TAG, "finish emergency exports timed out", it) }
        val dropped = GlesNative.nativeEmergencyClearPending()
        if (dropped > 0) V2AppLog.w(TAG, "drop uncovered emergency clip requests on stop count=$dropped")
        executor.shutdown()
    }

    private fun drainPending(stoppedAtWallClockMs: Long) {
        val outputs = GlesNative.nativeEmergencyExtractPending(outputDir.absolutePath, stoppedAtWallClockMs)
        for (path in outputs) {
            val out = File(path)
            if (out.isFile && out.length() > 0L) {
                val changed = V2PlaybackListCache.upsertVideo(appContext, out)
                if (changed) V2PlaybackCacheEvents.notifyChanged(appContext, "emergency_clip_generated", out.absolutePath)
                V2AppLog.i(TAG, "emergency clip generated file=${out.absolutePath}")
            }
        }
    }

    private companion object {
        private const val TAG = "V2CompositeRecorder"
    }
}
