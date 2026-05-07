package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class V2PlaybackContentLoader(
    context: Context,
    private val adapter: V2VideoPlaybackAdapter,
    private val playbackMode: () -> V2PlaybackMode,
    private val onLoadingStarted: () -> Unit,
    private val onGroupsLoaded: (List<V2VideoGroup>) -> Unit,
    private val onAutoSelect: (V2VideoGroup) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    private val thumbnailRepairExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { name = "EVCamPlaybackThumbRepair" }
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val requestedThumbnailKeys = mutableSetOf<String>()
    private val refreshRunning = AtomicBoolean(false)

    @Volatile private var loadGeneration = 0

    fun loadVideos(autoSelect: Boolean, preferCache: Boolean = true) {
        refreshRunning.set(false)
        val generation = nextGeneration()
        clearThumbnailRequests()
        onLoadingStarted()
        executor.execute {
            if (preferCache) {
                val cachedGroups = loadGroupsForCurrentMode()
                postIfCurrent(generation) {
                    adapter.replaceAll(cachedGroups)
                    if (autoSelect) cachedGroups.firstOrNull()?.let(onAutoSelect)
                    onGroupsLoaded(cachedGroups)
                }
                V2PlaybackCacheMaintainer.scheduleRefresh(appContext)
                return@execute
            }

            if (playbackMode() != V2PlaybackMode.PHOTO) {
                V2PlaybackCacheMaintainer.refreshNow(appContext, force = true)
            }
            val refreshedGroups = loadGroupsForCurrentMode()
            postIfCurrent(generation) {
                adapter.replaceAll(refreshedGroups)
                onGroupsLoaded(refreshedGroups)
            }
        }
    }

    fun refreshIncremental(onFinished: () -> Unit) {
        if (!refreshRunning.compareAndSet(false, true)) return
        val generation = nextGeneration()
        clearThumbnailRequests()
        val existingByKey = adapter.snapshot().associateBy { it.identityKey }
        executor.execute {
            if (playbackMode() == V2PlaybackMode.PHOTO) {
                val groups = V2VideoScanner.scanPhotoGroups(appContext)
                postIfCurrent(generation) {
                    adapter.replaceAll(groups)
                    onGroupsLoaded(groups)
                    finishRefreshIfCurrent(generation, onFinished)
                }
                return@execute
            }

            val scanned = ArrayList<V2VideoGroup>()
            val missingThumbnails = ArrayList<V2VideoGroup>()
            V2VideoScanner.scanGroupsIncremental(appContext, isCancelled = { generation != loadGeneration }) { group ->
                scanned += group
                val existing = existingByKey[group.identityKey]
                val merged = if (existing == null) {
                    group
                } else {
                    group.copy(
                        thumbnail = existing.thumbnail,
                        thumbnailPath = group.thumbnailPath ?: existing.thumbnailPath,
                    )
                }
                if (merged.thumbnail == null && merged.thumbnailPath.isNullOrBlank()) {
                    missingThumbnails += merged
                }
                postIfCurrent(generation) {
                    adapter.addOrUpdate(merged)
                    onGroupsLoaded(adapter.snapshot())
                }
            }
            if (generation == loadGeneration) V2VideoScanner.saveCachedGroups(appContext, scanned)
            postIfCurrent(generation) {
                onGroupsLoaded(adapter.snapshot())
            }
            repairMissingThumbnails(generation, missingThumbnails, onFinished)
        }
    }

    fun reloadVideosFromCache() {
        if (playbackMode() == V2PlaybackMode.PHOTO) return
        refreshRunning.set(false)
        val generation = nextGeneration()
        clearThumbnailRequests()
        executor.execute {
            val cachedGroups = loadGroupsForCurrentMode()
            postIfCurrent(generation) {
                adapter.replaceAll(cachedGroups)
                onGroupsLoaded(cachedGroups)
            }
        }
    }

    fun requestThumbnail(group: V2VideoGroup) {
        if (group.thumbnail != null || group.composite == null) return
        if (!requestedThumbnailKeys.add(group.identityKey)) return
        val generation = loadGeneration
        thumbnailExecutor.execute {
            val thumbnail = if (group.isPhoto) {
                group.composite?.let { V2VideoScanner.imageThumbnail(it) }
            } else {
                group.composite?.let { V2VideoScanner.cachedOrSidecarThumbnail(appContext, it, group.thumbnailPath) }
            } ?: return@execute
            postIfCurrent(generation) {
                adapter.updateThumbnail(group.identityKey, thumbnail)
            }
        }
    }

    fun clearThumbnailRequests() {
        requestedThumbnailKeys.clear()
    }

    fun shutdown() {
        loadGeneration += 1
        refreshRunning.set(false)
        executor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        thumbnailRepairExecutor.shutdownNow()
    }

    private fun nextGeneration(): Int {
        loadGeneration += 1
        return loadGeneration
    }

    private fun postIfCurrent(generation: Int, block: () -> Unit) {
        uiHandler.post {
            if (generation == loadGeneration) block()
        }
    }

    private fun repairMissingThumbnails(generation: Int, groups: List<V2VideoGroup>, onFinished: () -> Unit) {
        if (groups.isEmpty()) {
            postIfCurrent(generation) { finishRefreshIfCurrent(generation, onFinished) }
            return
        }
        val unique = groups.distinctBy { it.identityKey }
        thumbnailRepairExecutor.execute {
            try {
                for (group in unique) {
                    if (generation != loadGeneration) return@execute
                    val file = group.composite ?: continue
                    val thumbnail = V2PlaybackThumbnailLoader.generateThumbnailFromVideo(appContext, file)
                        ?: continue
                    postIfCurrent(generation) {
                        adapter.updateThumbnail(group.identityKey, thumbnail)
                    }
                    runCatching { Thread.sleep(THUMBNAIL_REPAIR_INTERVAL_MS) }
                }
            } finally {
                if (generation == loadGeneration) {
                    postIfCurrent(generation) { finishRefreshIfCurrent(generation, onFinished) }
                } else {
                    refreshRunning.set(false)
                }
            }
        }
    }

    private fun finishRefreshIfCurrent(generation: Int, onFinished: () -> Unit) {
        if (generation != loadGeneration) return
        refreshRunning.set(false)
        onFinished()
    }

    private fun loadGroupsForCurrentMode(): List<V2VideoGroup> = when (playbackMode()) {
        V2PlaybackMode.NORMAL -> V2VideoScanner.loadCachedGroups(appContext)
        V2PlaybackMode.PHOTO -> V2VideoScanner.scanPhotoGroups(appContext)
    }

    private companion object {
        const val THUMBNAIL_REPAIR_INTERVAL_MS = 500L
    }
}
