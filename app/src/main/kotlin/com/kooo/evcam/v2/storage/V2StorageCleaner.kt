package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import java.io.File

object V2StorageCleaner {
    private const val STALE_RECORDING_AGE_MS = 10 * 60 * 1000L

    fun cleanupForReservedSpace(context: Context, outputDir: File): V2StorageCleanupResult {
        val startedMs = SystemClock.elapsedRealtime()
        outputDir.mkdirs()
        val reservedBytes = V2StorageCleanupSettings.reservedSpaceBytes(context)
        val staleResult = cleanupStaleRecordingFiles(outputDir)
        if (reservedBytes <= 0L) return V2StorageCleanupResult(staleResult.first, staleResult.second, outputDir.usableSpace, reservedBytes)
            .also { logCleanupPerf(startedMs, it, "no_reserve") }

        var available = outputDir.usableSpace
        if (available >= reservedBytes) return V2StorageCleanupResult(staleResult.first, staleResult.second, available, reservedBytes)
            .also { logCleanupPerf(startedMs, it, "enough_space") }

        var deletedCount = staleResult.first
        var deletedBytes = staleResult.second
        val videos = outputDir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

        for (video in videos) {
            if (available >= reservedBytes) break
            val before = video.length().coerceAtLeast(0L)
            val thumb = File(video.parentFile, video.nameWithoutExtension + ".jpg")
            if (video.delete()) {
                deletedCount += 1
                deletedBytes += before
                if (thumb.exists()) {
                    val thumbBytes = thumb.length().coerceAtLeast(0L)
                    if (thumb.delete()) deletedBytes += thumbBytes
                }
                available = outputDir.usableSpace
                V2AppLog.w("V2StorageCleaner", "deleted old segment ${video.name} freed=${formatBytes(before)} available=${formatBytes(available)} reserve=${formatBytes(reservedBytes)}")
            } else {
                V2AppLog.w("V2StorageCleaner", "delete failed ${video.absolutePath}")
            }
        }

        val result = V2StorageCleanupResult(deletedCount, deletedBytes, available, reservedBytes)
        if (deletedCount > 0 || available < reservedBytes) {
            V2AppLog.w("V2StorageCleaner", "cleanup result deleted=$deletedCount freed=${formatBytes(deletedBytes)} available=${formatBytes(available)} reserve=${formatBytes(reservedBytes)}")
        }
        logCleanupPerf(startedMs, result, if (available >= reservedBytes) "recovered" else "low_space")
        return result
    }

    private fun logCleanupPerf(startedMs: Long, result: V2StorageCleanupResult, reason: String) {
        V2AppLog.perf("V2StoragePerf", "cleanup", SystemClock.elapsedRealtime() - startedMs, "reason=$reason deleted=${result.deletedCount} freed=${formatBytes(result.deletedBytes)} available=${formatBytes(result.availableBytes)} reserve=${formatBytes(result.reservedBytes)}")
    }

    private fun cleanupStaleRecordingFiles(outputDir: File): Pair<Int, Long> {
        val cutoff = System.currentTimeMillis() - STALE_RECORDING_AGE_MS
        var deletedCount = 0
        var deletedBytes = 0L
        val staleTemps = outputDir.listFiles { file ->
            file.isFile && file.name.endsWith(".mp4.recording", ignoreCase = true) && file.lastModified() in 1 until cutoff
        }.orEmpty()
        for (temp in staleTemps) {
            val before = temp.length().coerceAtLeast(0L)
            if (temp.delete()) {
                deletedCount += 1
                deletedBytes += before
                V2AppLog.w("V2StorageCleaner", "deleted stale temp segment ${temp.name} freed=${formatBytes(before)}")
            } else {
                V2AppLog.w("V2StorageCleaner", "delete stale temp failed ${temp.absolutePath}")
            }
        }
        return deletedCount to deletedBytes
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1fGB", gb)
    }
}
