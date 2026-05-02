package com.kooo.evcam.v2.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object V2RecordingThumbnailer {
    private val executor = Executors.newSingleThreadExecutor()

    fun generateFirstFrameAsync(context: Context, video: File) {
        val appContext = context.applicationContext
        executor.execute { generate(appContext, video) }
    }

    private fun generate(context: Context, video: File) {
        val startedMs = SystemClock.elapsedRealtime()
        runCatching {
            if (!video.isFile || !video.canRead() || video.length() <= 0L) return
            val out = thumbnailFile(video)
            if (out.exists() && out.length() > 0L && out.lastModified() >= video.lastModified()) {
                V2PlaybackListCache.updateThumbnail(context, video, out)
                return
            }

            val retriever = MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(video.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            } ?: return

            out.parentFile?.mkdirs()
            FileOutputStream(out).use { stream ->
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, stream)
            }
            out.setLastModified(video.lastModified())
            frame.recycle()
            V2PlaybackListCache.updateThumbnail(context, video, out)
            V2AppLog.perf("V2StoragePerf", "thumbnailGenerate", SystemClock.elapsedRealtime() - startedMs, "video=${video.name} videoBytes=${video.length()} thumbBytes=${out.length()}")
        }.onFailure { V2AppLog.w("RecordingThumbnailer", "thumbnail generation failed video=${video.absolutePath}", it) }
    }

    private fun thumbnailFile(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".jpg")
}
