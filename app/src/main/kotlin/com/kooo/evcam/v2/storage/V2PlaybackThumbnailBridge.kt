package com.kooo.evcam.v2.storage

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

object V2PlaybackThumbnailBridge {
    @JvmStatic
    fun ensureThumbnail(videoPath: String): String? = runCatching {
        val video = File(videoPath)
        if (!isPlayableVideoFile(video)) return null
        val target = File(video.parentFile, video.nameWithoutExtension + ".jpg")
        if (target.isFile && target.length() > 0L) return target.absolutePath
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.delete()

        val frame = MediaMetadataRetriever().useFrame(video) ?: return null
        try {
            FileOutputStream(temp).use { out ->
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 82, out)) return null
            }
            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                return null
            }
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target.absolutePath
        } finally {
            frame.recycle()
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.useFrame(video: File): Bitmap? {
        return try {
            setDataSource(video.absolutePath)
            val times = longArrayOf(0L, 1_000_000L, 3_000_000L)
            for (timeUs in times) {
                val frame = runCatching {
                    getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                }.getOrNull() ?: runCatching {
                    getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                if (frame != null) return frame.scaleForThumbnail()
            }
            null
        } finally {
            runCatching { release() }
        }
    }

    private fun Bitmap.scaleForThumbnail(): Bitmap {
        if (width <= 0 || height <= 0) return this
        if (width <= 320 && height <= 180) return this
        val wide = width.toLong() * 180L >= height.toLong() * 320L
        val targetWidth: Int
        val targetHeight: Int
        if (wide) {
            targetWidth = 320
            targetHeight = (height * 320 / width).coerceAtLeast(1)
        } else {
            targetHeight = 180
            targetWidth = (width * 180 / height).coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        if (scaled !== this) recycle()
        return scaled
    }

    private fun isPlayableVideoFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            file.extension.equals("mp4", ignoreCase = true) &&
            !file.name.endsWith(".recording", ignoreCase = true)
}
