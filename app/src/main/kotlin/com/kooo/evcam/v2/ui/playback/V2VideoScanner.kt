package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
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
        "^(\\d{8}_\\d{4}(?:\\d{2})?)(?:_[A-Za-z0-9]+)?(?:_\\d{2}(?:_\\d{3})?)?(?:(?:_seg\\d{3})?(?:_[A-Za-z0-9_]+)?_composite)?(?:_\\d{3})?\\.mp4$",
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

        val cursors = V2StoragePathHelper.playbackScanDirs(context).mapNotNull { dir ->
            if (isCancelled() || !dir.isDirectory || !dir.canRead()) return@mapNotNull null
            val files = dir.listFiles()
                .orEmpty()
                .filter { isPlayableVideoFile(it) && fileNamePattern.matches(it.name) }
                .sortedByDescending { videoKey(it).lowercase(Locale.US) }
            if (files.isEmpty()) null else ScanCursor(files)
        }.toMutableList()

        while (cursors.isNotEmpty()) {
            if (isCancelled()) return count
            val cursorIndex = cursors.indices.maxByOrNull { videoKey(cursors[it].current()).lowercase(Locale.US) } ?: break
            val cursor = cursors[cursorIndex]
            val file = cursor.current()
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
            if (!cursor.advance()) cursors.removeAt(cursorIndex)
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

    fun scanPhotoGroups(context: Context): List<V2VideoGroup> = V2StoragePathHelper.photoScanDirs(context)
        .asSequence()
        .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
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

    private class ScanCursor(private val files: List<File>) {
        private var index = 0
        fun current(): File = files[index]
        fun advance(): Boolean {
            index += 1
            return index < files.size
        }
    }

    private fun videoKey(file: File): String = file.nameWithoutExtension

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
        V2PlaybackListCache.updateThumbnail(context, file, thumb)
        return bitmap
    }

    private fun findThumbnailFile(file: File): File? =
        thumbnailCandidates(file).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }

    private fun decodeThumbnailFile(thumb: File): Bitmap? {
        return runCatching {
            BitmapFactory.Options().run {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(thumb.absolutePath, this)
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

    private fun defaultThumbnailFile(file: File): File = File(file.parentFile, file.nameWithoutExtension + ".bmp")

    private fun thumbnailCandidates(file: File): List<File> {
        val parent = file.parentFile ?: return emptyList()
        val stem = file.nameWithoutExtension
        val timestamp = fileNamePattern.matchEntire(file.name)?.groupValues?.getOrNull(1)
        val direct = listOf(
            defaultThumbnailFile(file),
            File(parent, "$stem.jpg"),
            File(parent, "$stem.jpeg"),
            File(parent, "${stem}_thumb.jpg"),
            File(parent, "${stem}_thumbnail.jpg"),
            File(parent, "${stem.removeSuffix("_composite")}.jpg"),
            File(parent, "${stem.removeSuffix("_composite")}.jpeg")
        )
        val prefixMatches = timestamp?.let { prefix ->
            parent.listFiles { candidate ->
                candidate.isFile && candidate.canRead() && candidate.length() > 0L &&
                    (candidate.extension.equals("jpg", ignoreCase = true) || candidate.extension.equals("jpeg", ignoreCase = true)) &&
                    candidate.name.startsWith(prefix, ignoreCase = true)
            }.orEmpty().sortedWith(
                compareByDescending<File> { it.nameWithoutExtension.equals(stem, ignoreCase = true) }
                    .thenByDescending { it.nameWithoutExtension.length }
                    .thenByDescending { it.name.lowercase(Locale.US) }
            )
        }.orEmpty()
        return (direct + prefixMatches).distinctBy { it.absolutePath }
    }

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
