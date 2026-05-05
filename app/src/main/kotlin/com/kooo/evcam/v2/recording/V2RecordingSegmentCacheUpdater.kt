package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Process
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackCacheEvents
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File
import java.util.concurrent.Executors

object V2RecordingSegmentCacheUpdater {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { name = "EVCamThumbnailWorker" }
    }
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (GlesNative.isLoaded) {
            runCatching { GlesNative.nativePrepareSegmentCacheCallback() }
                .onFailure { V2AppLog.w(TAG, "segment cache callback prepare failed", it) }
        }
    }

    @JvmStatic
    fun onNativeSegmentFinalized(path: String) {
        val context = appContext
        if (context == null) {
            V2AppLog.w(TAG, "segment cache upsert skipped: context unavailable file=$path")
            return
        }
        executor.execute {
            runCatching {
                val video = File(path)
                val thumbnailPath = if (GlesNative.isLoaded) GlesNative.nativeEnsurePlaybackThumbnail(path) else null
                val changed = V2PlaybackListCache.upsertFinalizedVideo(context, video)
                if (changed || thumbnailPath != null) V2PlaybackCacheEvents.notifyChanged(context, "segment_finalized", path)
                changed
            }.onSuccess { changed ->
                V2AppLog.i(TAG, "segment cache upserted changed=$changed file=$path")
            }.onFailure {
                V2AppLog.w(TAG, "segment cache upsert failed file=$path", it)
            }
        }
    }

    private const val TAG = "V2CompositeRecorder"
}
