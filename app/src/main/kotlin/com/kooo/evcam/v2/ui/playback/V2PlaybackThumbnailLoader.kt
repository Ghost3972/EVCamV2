package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File

internal object V2PlaybackThumbnailLoader {
    fun cachedThumbnail(file: File): Bitmap? {
        val thumb = findThumbnailFile(file) ?: return null
        return decodeThumbnailFile(thumb)
    }

    fun cachedThumbnailPath(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val thumb = File(path)
        if (!thumb.isFile || !thumb.canRead() || thumb.length() <= 0L) return null
        return decodeThumbnailFile(thumb)
    }

    fun imageThumbnail(file: File): Bitmap? = if (isImageFile(file)) decodeThumbnailFile(file) else null

    fun cachedThumbnail(context: Context, file: File): Bitmap? {
        val thumb = findThumbnailFile(file) ?: return null
        val bitmap = decodeThumbnailFile(thumb) ?: return null
        V2PlaybackListCache.updateThumbnail(context, file, thumb)
        return bitmap
    }

    fun cachedOrSidecarThumbnail(context: Context, file: File, cachedPath: String?): Bitmap? {
        val cached = cachedThumbnailPath(cachedPath)
        if (cached != null) return cached
        return cachedThumbnail(context, file)
    }

    fun generateThumbnailFromVideo(context: Context, file: File): Bitmap? {
        if (!GlesNative.isLoaded) return null
        val path = runCatching { GlesNative.nativeEnsurePlaybackThumbnail(file.absolutePath) }.getOrNull()
            ?: return null
        val thumb = File(path)
        val bitmap = decodeThumbnailFile(thumb) ?: return null
        V2PlaybackListCache.updateThumbnail(context, file, thumb)
        return bitmap
    }

    fun defaultThumbnailFile(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".jpg")

    private fun decodeThumbnailFile(thumb: File): Bitmap? {
        if (thumb.extension.equals("bmp", ignoreCase = true) && !isCompleteBmpFile(thumb)) return null
        return runCatching {
            BitmapFactory.Options().run {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(thumb.absolutePath, this)
                if (outWidth <= 0 || outHeight <= 0) return@run null
                inSampleSize = thumbnailSampleSize(outWidth, outHeight, 240, 160)
                inJustDecodeBounds = false
                BitmapFactory.decodeFile(thumb.absolutePath, this)
            }
        }.getOrNull()
    }

    private fun findThumbnailFile(file: File): File? =
        thumbnailCandidates(file).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }

    private fun thumbnailCandidates(file: File): List<File> {
        val parent = file.parentFile ?: return emptyList()
        val stem = file.nameWithoutExtension
        return listOf(
            defaultThumbnailFile(file),
            File(parent, "$stem.jpeg"),
            File(parent, "$stem.bmp"),
            File(parent, "${stem}_thumb.jpg"),
            File(parent, "${stem}_thumbnail.jpg"),
            File(parent, "${stem.removeSuffix("_composite")}.jpg"),
            File(parent, "${stem.removeSuffix("_composite")}.jpeg"),
            File(parent, "${stem.removeSuffix("_composite")}.bmp")
        ).distinctBy { it.absolutePath }
    }

    private fun isCompleteBmpFile(file: File): Boolean = runCatching {
        if (file.length() < BMP_HEADER_BYTES) return false
        val header = ByteArray(BMP_HEADER_BYTES)
        file.inputStream().use { if (it.read(header) != BMP_HEADER_BYTES) return false }
        if (header[0] != 'B'.code.toByte() || header[1] != 'M'.code.toByte()) return false
        val width = readLe32(header, 18)
        val height = readLe32(header, 22)
        val bitsPerPixel = readLe16(header, 28)
        val dataOffset = readLe32(header, 10)
        if (width <= 0 || height <= 0 || dataOffset < BMP_HEADER_BYTES) return false
        val bytesPerPixel = when (bitsPerPixel) {
            24 -> 3
            32 -> 4
            else -> return false
        }
        val rowStride = ((width * bytesPerPixel + 3) / 4) * 4
        val expected = dataOffset.toLong() + rowStride.toLong() * height.toLong()
        file.length() >= expected
    }.getOrDefault(false)

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun thumbnailSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
                sample *= 2
            }
        }
        return sample.coerceAtLeast(1)
    }

    private fun isImageFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            (file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true) || file.extension.equals("png", ignoreCase = true))

    private const val BMP_HEADER_BYTES = 54
}
