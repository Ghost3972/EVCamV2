package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import com.kooo.evcam.v2.storage.V2PlaybackListEntry
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
                val thumbnailFile = V2PlaybackThumbnailLoader.defaultThumbnailFile(file)
                    .takeIf { it.isFile && it.length() > 0L }
                onGroup(V2VideoGroup(timestamp = key, composite = file, thumbnailPath = thumbnailFile?.absolutePath))
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
            val thumbnailFile = V2PlaybackThumbnailLoader.defaultThumbnailFile(file)
                .takeIf { it.isFile && it.length() > 0L }
            V2PlaybackListEntry(
                key = group.timestamp,
                path = file.absolutePath,
                length = file.length(),
                modified = file.lastModified(),
                thumbnailPath = thumbnailFile?.absolutePath,
                thumbnailModified = thumbnailFile?.lastModified() ?: 0L,
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

    fun cachedThumbnail(file: File): Bitmap? = V2PlaybackThumbnailLoader.cachedThumbnail(file)

    fun cachedThumbnailPath(path: String?): Bitmap? = V2PlaybackThumbnailLoader.cachedThumbnailPath(path)

    fun imageThumbnail(file: File): Bitmap? = V2PlaybackThumbnailLoader.imageThumbnail(file)

    fun cachedThumbnail(context: Context, file: File): Bitmap? =
        V2PlaybackThumbnailLoader.cachedThumbnail(context, file)

    fun cachedOrSidecarThumbnail(context: Context, file: File, cachedPath: String?): Bitmap? =
        V2PlaybackThumbnailLoader.cachedOrSidecarThumbnail(context, file, cachedPath)
}
