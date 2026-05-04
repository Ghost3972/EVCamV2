package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File
import java.util.concurrent.Executors

internal class V2PlaybackDeletionCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    fun delete(group: V2VideoGroup, file: File, onComplete: (Boolean) -> Unit) {
        executor.execute {
            val success = runCatching {
                val targets = group.files.ifEmpty { listOf(file) }
                    .filter { it.exists() }
                    .distinctBy { it.absolutePath }
                val refreshedJson = GlesNative.nativeDeleteVideosAndBuildPlaybackCache(
                    targets.map { it.absolutePath }.toTypedArray(),
                    V2StoragePathHelper.playbackScanDirs(appContext).map { it.absolutePath }.toTypedArray(),
                )
                if (refreshedJson != null) {
                    V2PlaybackListCache.replaceWithNativeJson(appContext, refreshedJson)
                } else {
                    V2PlaybackListCache.removeVideo(appContext, file)
                    V2PlaybackCacheMaintainer.refreshNow(appContext)
                }
                targets.isNotEmpty() && targets.none { it.exists() }
            }.getOrDefault(false)
            uiHandler.post { onComplete(success) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
