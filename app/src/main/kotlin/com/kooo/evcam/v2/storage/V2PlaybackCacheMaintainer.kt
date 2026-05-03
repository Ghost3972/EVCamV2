package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object V2PlaybackCacheMaintainer {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var recordingActive = false
    @Volatile private var lastRefreshElapsedMs = 0L

    fun setRecordingActive(active: Boolean) {
        if (recordingActive == active) return
        recordingActive = active
        V2AppLog.i("PlaybackCacheMaintainer", "recordingActive=$active")
    }

    fun scheduleRefresh(context: Context) {
        val appContext = context.applicationContext
        if (recordingActive) {
            V2AppLog.i("PlaybackCacheMaintainer", "refresh skipped: recording active")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshElapsedMs < MIN_REFRESH_INTERVAL_MS) {
            V2AppLog.i("PlaybackCacheMaintainer", "refresh skipped: cache recently refreshed")
            return
        }
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (recordingActive) {
                    V2AppLog.i("PlaybackCacheMaintainer", "refresh aborted: recording active")
                } else {
                    refreshNow(appContext)
                }
            } finally {
                running.set(false)
            }
        }
    }

    fun refreshNow(context: Context, force: Boolean = false): Int {
        val startedMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        if (recordingActive && !force) {
            V2AppLog.i("PlaybackCacheMaintainer", "refreshNow skipped: recording active")
            return V2PlaybackListCache.loadFast(appContext).size
        }
        return runCatching {
            val cached = V2PlaybackListCache.loadFast(appContext)
            val scanDirs = V2StoragePathHelper.playbackScanDirs(appContext)
            val json = nativePlaybackCacheJson(scanDirs, ensureThumbnails = force)
            val entryCount = V2PlaybackListCache.replaceWithNativeJson(appContext, json)
            lastRefreshElapsedMs = SystemClock.elapsedRealtime()
            V2AppLog.perf("V2StoragePerf", "playbackCacheRefresh", SystemClock.elapsedRealtime() - startedMs, "dirs=${scanDirs.size} entries=$entryCount cached=${cached.size} thumbnails=${if (force) "native" else "skip"} backend=native force=$force")
            entryCount
        }.onFailure {
            V2AppLog.w("PlaybackCacheMaintainer", "refresh failed", it)
        }.getOrDefault(0)
    }

    private fun nativePlaybackCacheJson(scanDirs: List<java.io.File>, ensureThumbnails: Boolean): String {
        check(GlesNative.isLoaded) { "native playback cache unavailable" }
        val paths = scanDirs.map { it.absolutePath }.toTypedArray()
        return if (ensureThumbnails) {
            GlesNative.nativeBuildPlaybackCacheWithThumbnails(paths) ?: "[]"
        } else {
            GlesNative.nativeBuildPlaybackCache(paths) ?: "[]"
        }
    }

    private const val MIN_REFRESH_INTERVAL_MS = 60_000L
}
