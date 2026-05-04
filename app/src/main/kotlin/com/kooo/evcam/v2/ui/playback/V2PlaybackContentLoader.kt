package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import java.util.concurrent.Executors

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
    private val uiHandler = Handler(Looper.getMainLooper())
    private val requestedThumbnailKeys = mutableSetOf<String>()

    @Volatile private var loadGeneration = 0

    fun loadVideos(autoSelect: Boolean, preferCache: Boolean = true) {
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

    fun reloadVideosFromCache() {
        if (playbackMode() == V2PlaybackMode.PHOTO) return
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
        executor.shutdownNow()
        thumbnailExecutor.shutdownNow()
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

    private fun loadGroupsForCurrentMode(): List<V2VideoGroup> = when (playbackMode()) {
        V2PlaybackMode.NORMAL -> V2VideoScanner.loadCachedGroups(appContext, eventOnly = false)
        V2PlaybackMode.EVENT -> V2VideoScanner.loadCachedGroups(appContext, eventOnly = true)
        V2PlaybackMode.PHOTO -> V2VideoScanner.scanPhotoGroups(appContext)
    }
}
