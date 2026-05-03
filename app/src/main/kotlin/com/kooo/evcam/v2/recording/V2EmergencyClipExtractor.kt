package com.kooo.evcam.v2.recording

import android.content.Context
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File

object V2EmergencyClipExtractor {
    fun extract(
        context: Context,
        outputDir: File,
        clipStartWallClockMs: Long,
        clipEndWallClockMs: Long,
        sources: List<V2EmergencySourceSegment>,
    ): File? = runCatching {
        val usableSources = sources
            .filter { it.file.isFile && it.file.length() > 0L && it.endWallClockMs > clipStartWallClockMs && it.startWallClockMs < clipEndWallClockMs }
            .sortedBy { it.startWallClockMs }
        if (usableSources.isEmpty()) return null

        outputDir.mkdirs()
        val finalFile = V2SegmentFileNamer.uniqueFile(outputDir, V2SegmentFileNamer.timestamp(clipStartWallClockMs), EVENT_SUFFIX)
        val tempFile = File(outputDir, finalFile.name + ".recording")
        tempFile.delete()

        val writtenSamples = GlesNative.nativeExtractEmergencyClip(
            outputPath = tempFile.absolutePath,
            finalOutputPath = finalFile.absolutePath,
            clipStartWallClockMs = clipStartWallClockMs,
            clipEndWallClockMs = clipEndWallClockMs,
            sourcePaths = usableSources.map { it.file.absolutePath }.toTypedArray(),
            sourceStartWallClockMs = usableSources.map { it.startWallClockMs }.toLongArray(),
            sourceEndWallClockMs = usableSources.map { it.endWallClockMs }.toLongArray(),
        )

        if (writtenSamples <= 0L || !finalFile.isFile || finalFile.length() <= 0L) {
            V2AppLog.w(TAG, "native emergency clip extraction produced no output: ${GlesNative.getLastError()}")
            tempFile.delete()
            return null
        }
        val out = finalFile
        V2PlaybackListCache.upsertVideo(context.applicationContext, out)
        V2AppLog.i(TAG, "emergency clip generated file=${out.absolutePath} sources=${usableSources.size} samples=$writtenSamples")
        out
    }.onFailure {
        V2AppLog.e(TAG, "emergency clip extraction failed", it)
    }.getOrNull()

    private const val TAG = "EmergencyClipExtractor"
    private const val EVENT_SUFFIX = "_event"
}
