package com.kooo.evcam.v2.recording

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StorageCleaner
import com.kooo.evcam.v2.storage.V2StorageCleanupResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class V2RecordingStorageCleanupScheduler(
    private val context: Context,
    private val outputDir: File,
    private val logTag: String,
) {
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cleanupFuture: Future<V2StorageCleanupResult>? = null

    fun schedule() {
        val future = cleanupFuture
        if (future != null && !future.isDone) return
        cleanupFuture = cleanupExecutor.submit<V2StorageCleanupResult> {
            runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
                .onSuccess { logCleanupResult("background", it) }
                .getOrElse {
                    V2AppLog.w(logTag, "storage cleanup background failed", it)
                    V2StorageCleanupResult(0, 0L, outputDir.usableSpace, 0L)
                }
        }
    }

    fun cancelAndShutdown() {
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
    }

    private fun logCleanupResult(stage: String, result: V2StorageCleanupResult) {
        if (result.deletedCount > 0) {
            V2AppLog.w(logTag, "storage cleanup $stage deleted=${result.deletedCount} freed=${V2StorageCleaner.formatBytes(result.deletedBytes)} available=${V2StorageCleaner.formatBytes(result.availableBytes)} reserve=${V2StorageCleaner.formatBytes(result.reservedBytes)}")
        }
    }
}
