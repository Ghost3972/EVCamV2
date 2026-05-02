package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object V2PlaybackCacheMaintainer {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var recordingActive = false
    @Volatile private var lastRefreshElapsedMs = 0L
    private val videoNamePattern = Regex(
        "^(\\d{8}_\\d{4}(?:\\d{2})?)(?:_[A-Za-z0-9]+)?(?:_\\d{2}(?:_\\d{3})?)?(?:(?:_seg\\d{3})?(?:_[A-Za-z0-9_]+)?_composite)?(?:_\\d{3})?\\.mp4$",
        RegexOption.IGNORE_CASE
    )

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

    fun refreshNow(context: Context): Int {
        val startedMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        if (recordingActive) {
            V2AppLog.i("PlaybackCacheMaintainer", "refreshNow skipped: recording active")
            return V2PlaybackListCache.loadFast(appContext).size
        }
        return runCatching {
            val cached = V2PlaybackListCache.loadFast(appContext)
            val cachedByPath = cached.associateBy { it.path }
            val cachedByKey = cached.associateBy { it.key }
            var scannedFiles = 0
            val scanDirs = V2StoragePathHelper.playbackScanDirs(appContext)
            val entries = scanDirs
                .asSequence()
                .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
                .onEach { scannedFiles += 1 }
                .filter { isPlayableVideoFile(it) && videoNamePattern.matches(it.name) }
                .distinctBy { it.absolutePath }
                .map { video ->
                    val cachedEntry = cachedByPath[video.absolutePath] ?: cachedByKey[video.nameWithoutExtension]
                    val thumbnail = validCachedThumbnail(video, cachedEntry) ?: existingThumbnail(video)
                    V2PlaybackListEntry(
                        key = video.nameWithoutExtension,
                        path = video.absolutePath,
                        length = video.length(),
                        modified = video.lastModified(),
                        thumbnailPath = thumbnail?.absolutePath,
                        thumbnailModified = thumbnail?.lastModified() ?: 0L,
                    )
                }
                .sortedByDescending { it.key.lowercase(Locale.US) }
                .toList()
            V2PlaybackListCache.save(appContext, entries)
            lastRefreshElapsedMs = SystemClock.elapsedRealtime()
            V2AppLog.perf("V2StoragePerf", "playbackCacheRefresh", SystemClock.elapsedRealtime() - startedMs, "dirs=${scanDirs.size} scanned=$scannedFiles entries=${entries.size} cached=${cached.size}")
            entries.size
        }.onFailure {
            V2AppLog.w("PlaybackCacheMaintainer", "refresh failed", it)
        }.getOrDefault(0)
    }

    private fun validCachedThumbnail(video: File, entry: V2PlaybackListEntry?): File? {
        val path = entry?.thumbnailPath ?: return null
        val thumbnail = File(path)
        if (!thumbnail.isFile || !thumbnail.canRead() || thumbnail.length() <= 0L) return null
        if (entry.thumbnailModified > 0L && thumbnail.lastModified() != entry.thumbnailModified) return null
        return thumbnail.takeIf { it.lastModified() >= video.lastModified() }
    }

    private fun existingThumbnail(video: File): File? = thumbnailFile(video)
        .takeIf { it.isFile && it.canRead() && it.length() > 0L && it.lastModified() >= video.lastModified() }

    private fun thumbnailFile(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".jpg")

    private fun isPlayableVideoFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            file.extension.equals("mp4", ignoreCase = true) &&
            !file.name.endsWith(".recording", ignoreCase = true)

    private const val MIN_REFRESH_INTERVAL_MS = 60_000L
}
