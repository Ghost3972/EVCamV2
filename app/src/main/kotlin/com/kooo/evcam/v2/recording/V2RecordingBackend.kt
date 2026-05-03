package com.kooo.evcam.v2.recording

import android.os.SystemClock
import android.view.Surface
import com.kooo.evcam.v2.nativebridge.GlesNative
import java.io.File

internal data class V2SegmentWriterConfig(
    val outputDir: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val fileSuffix: String,
    val mimeType: String,
)

internal class V2NativeSegmentWriter(
    private val config: V2SegmentWriterConfig,
) {
    val nativeHandle: Long = if (GlesNative.isLoaded) {
        GlesNative.createNativeSegmentWriter(
            config.width,
            config.height,
            config.fps,
            config.bitrate,
            config.mimeType,
        )
    } else {
        0L
    }

    private var currentFile: File? = null
    private var tempFile: File? = null
    private var segmentWallClockMs: Long = 0L
    private var segmentStartedElapsedMs: Long = 0L

    val surface: Surface? by lazy {
        if (nativeHandle != 0L) GlesNative.nativeSegmentWriterInputSurface(nativeHandle) else null
    }

    fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        check(nativeHandle != 0L) { "Native recording backend unavailable: ${config.mimeType} ${config.width}x${config.height}" }
        val finalFile = V2SegmentFileNamer.uniqueFile(
            config.outputDir,
            V2SegmentFileNamer.timestamp(segmentWallClockMs),
            config.fileSuffix,
        )
        val recordingFile = File(finalFile.parentFile, finalFile.name + ".recording")
        check(GlesNative.nativeSegmentWriterStartSegment(nativeHandle, recordingFile.absolutePath, segmentIndex, segmentWallClockMs)) {
            "Native recording backend start failed: ${GlesNative.getLastError()}"
        }
        currentFile = finalFile
        tempFile = recordingFile
        this.segmentWallClockMs = segmentWallClockMs
        segmentStartedElapsedMs = SystemClock.elapsedRealtime()
        return finalFile
    }

    fun finishAndReleaseBlocking(): File? {
        if (nativeHandle == 0L) return null
        val finalFile = currentFile ?: return null
        val recordingFile = tempFile ?: finalFile
        val finished = GlesNative.nativeSegmentWriterFinish(nativeHandle, finalFile.absolutePath)
        return if (finished && finalFile.exists() && finalFile.length() > 0L) {
            finalFile
        } else {
            if (recordingFile.exists()) recordingFile.delete()
            null
        }
    }

    fun releaseBlocking(): File? {
        if (nativeHandle != 0L) GlesNative.nativeSegmentWriterRelease(nativeHandle)
        tempFile?.takeIf { it.exists() }?.delete()
        return null
    }

    fun currentFile(): File? = currentFile
    fun segmentStartedElapsedMs(): Long = segmentStartedElapsedMs
    fun mediaStartWallClockMs(): Long = segmentWallClockMs
}
