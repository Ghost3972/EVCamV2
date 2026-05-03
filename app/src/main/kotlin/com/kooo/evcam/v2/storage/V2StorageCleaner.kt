package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import java.io.File

object V2StorageCleaner {
    fun cleanupForReservedSpace(context: Context, outputDir: File): V2StorageCleanupResult {
        val startedMs = SystemClock.elapsedRealtime()
        outputDir.mkdirs()
        val reservedBytes = V2StorageCleanupSettings.reservedSpaceBytes(context)
        val native = if (GlesNative.isLoaded) {
            runCatching {
                GlesNative.nativeCleanupStorage(
                    outputDir = outputDir.absolutePath,
                    reservedBytes = reservedBytes,
                    availableBytes = outputDir.usableSpace,
                )
            }.getOrElse {
                V2AppLog.w("V2StorageCleaner", "native cleanup failed", it)
                longArrayOf()
            }
        } else {
            longArrayOf()
        }
        val result = if (native.size >= 3) {
            V2StorageCleanupResult(native[0].toInt(), native[1], outputDir.usableSpace.coerceAtLeast(native[2]), reservedBytes)
        } else {
            V2StorageCleanupResult(0, 0L, outputDir.usableSpace, reservedBytes)
        }
        if (result.deletedCount > 0 || result.availableBytes < reservedBytes) {
            V2AppLog.w("V2StorageCleaner", "cleanup result deleted=${result.deletedCount} freed=${formatBytes(result.deletedBytes)} available=${formatBytes(result.availableBytes)} reserve=${formatBytes(reservedBytes)}")
        }
        logCleanupPerf(startedMs, result, if (result.availableBytes >= reservedBytes) "native" else "native_low_space")
        return result
    }

    private fun logCleanupPerf(startedMs: Long, result: V2StorageCleanupResult, reason: String) {
        V2AppLog.perf("V2StoragePerf", "cleanup", SystemClock.elapsedRealtime() - startedMs, "reason=$reason deleted=${result.deletedCount} freed=${formatBytes(result.deletedBytes)} available=${formatBytes(result.availableBytes)} reserve=${formatBytes(result.reservedBytes)}")
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1fGB", gb)
    }
}
