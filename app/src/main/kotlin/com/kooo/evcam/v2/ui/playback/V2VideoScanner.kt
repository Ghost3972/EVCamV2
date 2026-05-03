package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackListEntry
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import com.kooo.evcam.v2.storage.V2StoragePathHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class V2VideoGroup(
    val timestamp: String,
    val composite: File?,
    val isPhoto: Boolean = false,
    val thumbnail: Bitmap? = null,
    val thumbnailPath: String? = null,
    private val cachedTotalBytes: Long? = null,
    private val cachedModified: Long? = null,
) {
    val identityKey: String = composite?.absolutePath ?: timestamp
    val files: List<File> = listOfNotNull(composite)
    val count: Int = files.size
    val totalBytes: Long = cachedTotalBytes ?: files.sumOf { it.length() }
    private val parsedDate: Date = V2VideoScanner.parseTimestamp(timestamp) ?: Date(cachedModified ?: files.maxOfOrNull { it.lastModified() } ?: 0L)
    val displayYear: String = runCatching {
        SimpleDateFormat("yyyy", Locale.getDefault()).format(parsedDate)
    }.getOrDefault("")
    val displayDate: String = runCatching {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(parsedDate)
    }.getOrDefault("")
    val displayTime: String = runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(parsedDate)
    }.getOrDefault(timestamp)
}

object V2VideoScanner {
    private val fileNamePattern = Regex(
        "^(\\d{8}_\\d{4}(?:\\d{2})?)(?:_[A-Za-z0-9]+)*(?:_\\d{2}(?:_\\d{3})?)?(?:(?:_seg\\d{3})?(?:_[A-Za-z0-9_]+)?_composite)?(?:_\\d{3})?\\.mp4$",
        RegexOption.IGNORE_CASE
    )
    private val imageNamePattern = Regex("^(\\d{8}_\\d{4}(?:\\d{2})?)_snapshot(?:_\\d{3})?\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)

    fun parseTimestamp(timestamp: String): Date? {
        val prefix = Regex("^(\\d{8}_\\d{4}(?:\\d{2})?)").find(timestamp)?.value ?: return null
        val pattern = if (prefix.length == 15) "yyyyMMdd_HHmmss" else "yyyyMMdd_HHmm"
        return runCatching { SimpleDateFormat(pattern, Locale.US).parse(prefix) }.getOrNull()
    }

    fun scanGroupsIncremental(
        context: Context,
        isCancelled: () -> Boolean = { false },
        onGroup: (V2VideoGroup) -> Unit
    ): Int {
        val emitted = HashSet<String>()
        var count = 0
        var lastYieldMs = SystemClock.uptimeMillis()

        val files = nativePlaybackFiles(context)
            .filter { isPlayableVideoFile(it) && fileNamePattern.matches(it.name) }
            .distinctBy { it.absolutePath }
            .sortedByDescending { videoKey(it).lowercase(Locale.US) }

        for (file in files) {
            if (isCancelled()) return count
            val key = videoKey(file)
            if (emitted.add(file.absolutePath)) {
                onGroup(V2VideoGroup(timestamp = key, composite = file))
                count += 1
                val now = SystemClock.uptimeMillis()
                if (now - lastYieldMs >= 8L) {
                    Thread.yield()
                    lastYieldMs = now
                }
            }
        }
        return count
    }

    fun loadCachedGroups(context: Context): List<V2VideoGroup> = runCatching {
        V2PlaybackListCache.loadFast(context).map { entry ->
            V2VideoGroup(
                timestamp = entry.key,
                composite = File(entry.path),
                thumbnailPath = entry.thumbnailPath,
                cachedTotalBytes = entry.length,
                cachedModified = entry.modified,
            )
        }
    }.getOrDefault(emptyList())

    fun loadCachedGroups(context: Context, eventOnly: Boolean): List<V2VideoGroup> = loadCachedGroups(context)
        .filter { group -> group.composite?.name?.contains("_event", ignoreCase = true) == eventOnly }

    fun scanPhotoGroups(context: Context): List<V2VideoGroup> = nativePlaybackImages(context)
        .asSequence()
        .filter { isImageFile(it) && imageNamePattern.matches(it.name) }
        .distinctBy { it.absolutePath }
        .sortedWith(compareByDescending<File> { it.nameWithoutExtension.lowercase(Locale.US) }.thenByDescending { it.absolutePath })
        .map { file -> V2VideoGroup(timestamp = file.nameWithoutExtension, composite = file, isPhoto = true, thumbnailPath = file.absolutePath, cachedTotalBytes = file.length(), cachedModified = file.lastModified()) }
        .toList()

    fun loadCachedGroupsIncremental(
        context: Context,
        isCancelled: () -> Boolean = { false },
        onGroup: (V2VideoGroup) -> Unit
    ): Int = runCatching {
        var count = 0
        V2PlaybackListCache.loadFast(context).forEach { entry ->
            if (isCancelled()) return count
            val file = File(entry.path)
            onGroup(V2VideoGroup(
                timestamp = entry.key,
                composite = file,
                thumbnailPath = entry.thumbnailPath,
                cachedTotalBytes = entry.length,
                cachedModified = entry.modified,
            ))
            count += 1
        }
        count
    }.getOrDefault(0)

    fun saveCachedGroups(context: Context, groups: List<V2VideoGroup>) {
        V2PlaybackListCache.save(context, groups.mapNotNull { group ->
            val file = group.composite ?: return@mapNotNull null
            V2PlaybackListEntry(
                key = group.timestamp,
                path = file.absolutePath,
                length = file.length(),
                modified = file.lastModified(),
                thumbnailPath = defaultThumbnailFile(file).takeIf { it.isFile && it.length() > 0L }?.absolutePath,
                thumbnailModified = defaultThumbnailFile(file).takeIf { it.isFile && it.length() > 0L }?.lastModified() ?: 0L,
            )
        })
    }

    private fun videoKey(file: File): String = file.nameWithoutExtension

    private fun nativePlaybackFiles(context: Context): List<File> {
        if (!GlesNative.isLoaded) return emptyList()
        val dirs = V2StoragePathHelper.playbackScanDirs(context).map { it.absolutePath }.toTypedArray()
        return runCatching { GlesNative.nativeListPlaybackVideos(dirs).map(::File) }.getOrDefault(emptyList())
    }

    private fun nativePlaybackImages(context: Context): List<File> {
        if (!GlesNative.isLoaded) return emptyList()
        val dirs = V2StoragePathHelper.photoScanDirs(context).map { it.absolutePath }.toTypedArray()
        return runCatching { GlesNative.nativeListPlaybackImages(dirs).map(::File) }.getOrDefault(emptyList())
    }

    private fun isPlayableVideoFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            file.extension.equals("mp4", ignoreCase = true) &&
            !file.name.endsWith(".recording", ignoreCase = true)

    private fun isImageFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            (file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true) || file.extension.equals("png", ignoreCase = true))

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
        val cachedFile = thumb
        V2PlaybackListCache.updateThumbnail(context, file, cachedFile)
        return bitmap
    }

    fun cachedOrSidecarThumbnail(context: Context, file: File, cachedPath: String?): Bitmap? {
        val cached = cachedThumbnailPath(cachedPath)
        if (cached != null) return cached
        return cachedThumbnail(context, file)
    }

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

    private fun cachedThumbnailFromEntry(context: Context, file: File, entry: V2PlaybackListEntry): Bitmap? {
        val cached = entry.thumbnailPath?.let { path ->
            val thumb = File(path)
            if (thumb.isFile && thumb.canRead() && thumb.length() > 0L &&
                (entry.thumbnailModified <= 0L || thumb.lastModified() == entry.thumbnailModified)
            ) {
                decodeThumbnailFile(thumb)?.also { V2PlaybackListCache.updateThumbnail(context, file, thumb) }
            } else {
                null
            }
        }
        return cached ?: cachedThumbnail(context, file)
    }

    private fun defaultThumbnailFile(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".jpg")

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

    private const val BMP_HEADER_BYTES = 54

    private fun findThumbnailFile(file: File): File? =
        thumbnailCandidates(file).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }

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
}
