package com.kooo.evcam.v2.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object V2PlaybackListCache {
    private const val CACHE_FILE_NAME = "v2_playback_video_list.json"
    @Volatile private var memoryEntries: List<Entry> = emptyList()

    data class Entry(
        val key: String,
        val path: String,
        val length: Long,
        val modified: Long,
        val thumbnailPath: String? = null,
        val thumbnailModified: Long = 0L,
    )

    fun load(context: Context): List<Entry> = runCatching {
        val cache = cacheFile(context)
        if (!cache.isFile || !cache.canRead() || cache.length() <= 0L) return emptyList()
        val array = JSONArray(cache.readText())
        val entries = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            val file = File(path)
            val expectedLength = item.optLong("length", -1L)
            val expectedModified = item.optLong("modified", -1L)
            if (!isPlayableVideoFile(file)) continue
            if (expectedLength > 0L && file.length() != expectedLength) continue
            if (expectedModified > 0L && file.lastModified() != expectedModified) continue
            entries += Entry(
                key = item.optString("key", file.nameWithoutExtension).ifBlank { file.nameWithoutExtension },
                path = file.absolutePath,
                length = file.length(),
                modified = file.lastModified(),
                thumbnailPath = item.optString("thumbnailPath").ifBlank { null },
                thumbnailModified = item.optLong("thumbnailModified", 0L),
            )
        }
        entries.sortedByDescending { it.key.lowercase(Locale.US) }
    }.getOrDefault(emptyList())

    fun loadFast(context: Context): List<Entry> = runCatching {
        memoryEntries.takeIf { it.isNotEmpty() }?.let { return it }
        val cache = cacheFile(context)
        if (!cache.isFile || !cache.canRead() || cache.length() <= 0L) return emptyList()
        val array = JSONArray(cache.readText())
        val entries = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            val fileName = File(path).nameWithoutExtension
            entries += Entry(
                key = item.optString("key", fileName).ifBlank { fileName },
                path = path,
                length = item.optLong("length", 0L),
                modified = item.optLong("modified", 0L),
                thumbnailPath = item.optString("thumbnailPath").ifBlank { null },
                thumbnailModified = item.optLong("thumbnailModified", 0L),
            )
        }
        entries.sortedByDescending { it.key.lowercase(Locale.US) }.also { memoryEntries = it }
    }.getOrDefault(emptyList())

    fun save(context: Context, entries: List<Entry>) {
        runCatching {
            val normalized = entries.distinctBy { it.key }.sortedByDescending { it.key.lowercase(Locale.US) }
            memoryEntries = normalized
            val array = JSONArray()
            normalized.forEach { entry ->
                val file = File(entry.path)
                if (!isPlayableVideoFile(file)) return@forEach
                array.put(JSONObject().apply {
                    put("key", entry.key)
                    put("path", file.absolutePath)
                    put("length", file.length())
                    put("modified", file.lastModified())
                    entry.thumbnailPath?.let { put("thumbnailPath", it) }
                    if (entry.thumbnailModified > 0L) put("thumbnailModified", entry.thumbnailModified)
                })
            }
            writeArray(context, array)
        }
    }

    @Synchronized
    fun upsertVideo(context: Context, video: File) {
        if (!isPlayableVideoFile(video)) return
        val thumbnail = defaultThumbnailFile(video).takeIf { it.isFile && it.length() > 0L }
        val next = Entry(
            key = video.nameWithoutExtension,
            path = video.absolutePath,
            length = video.length(),
            modified = video.lastModified(),
            thumbnailPath = thumbnail?.absolutePath,
            thumbnailModified = thumbnail?.lastModified() ?: 0L,
        )
        save(context, (loadFast(context).filterNot { it.key == next.key } + next))
    }

    @Synchronized
    fun updateThumbnail(context: Context, video: File, thumbnail: File) {
        if (!isPlayableVideoFile(video) || !thumbnail.isFile || thumbnail.length() <= 0L) return
        val entries = loadFast(context).filterNot { it.key == video.nameWithoutExtension }
        val next = Entry(
            key = video.nameWithoutExtension,
            path = video.absolutePath,
            length = video.length(),
            modified = video.lastModified(),
            thumbnailPath = thumbnail.absolutePath,
            thumbnailModified = thumbnail.lastModified(),
        )
        save(context, entries + next)
    }

    @Synchronized
    fun removeVideo(context: Context, video: File) {
        val path = video.absolutePath
        val key = video.nameWithoutExtension
        save(context, loadFast(context).filterNot { it.path == path || it.key == key })
    }

    private fun writeArray(context: Context, array: JSONArray) {
        val cache = cacheFile(context)
        cache.parentFile?.mkdirs()
        cache.writeText(array.toString())
    }

    private fun cacheFile(context: Context): File = File(context.cacheDir, CACHE_FILE_NAME)

    private fun defaultThumbnailFile(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".jpg")

    private fun isPlayableVideoFile(file: File): Boolean =
        file.isFile && file.exists() && file.canRead() && file.length() > 0L &&
            file.extension.equals("mp4", ignoreCase = true) &&
            !file.name.endsWith(".recording", ignoreCase = true)
}
