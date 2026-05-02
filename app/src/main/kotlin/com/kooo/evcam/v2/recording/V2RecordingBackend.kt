package com.kooo.evcam.v2.recording

import android.content.Context
import java.io.File

internal enum class V2RecordingBackend {
    AndroidMedia,
    NativeExperimental,
}

internal data class V2SegmentWriterConfig(
    val context: Context,
    val outputDir: File,
    val metrics: RecordingMetrics,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val recordingSessionId: String,
    val fileSuffix: String,
    val mimeType: String,
)

internal object V2SegmentWriterFactory {
    fun create(
        backend: V2RecordingBackend,
        config: V2SegmentWriterConfig,
    ): V2SegmentWriter = when (backend) {
        V2RecordingBackend.AndroidMedia -> EncoderSegmentWriter(
            context = config.context,
            outputDir = config.outputDir,
            metrics = config.metrics,
            width = config.width,
            height = config.height,
            fps = config.fps,
            bitrate = config.bitrate,
            recordingSessionId = config.recordingSessionId,
            fileSuffix = config.fileSuffix,
            mimeType = config.mimeType,
        )
        V2RecordingBackend.NativeExperimental -> V2NativeSegmentWriterStub(config)
    }
}

private class V2NativeSegmentWriterStub(
    private val config: V2SegmentWriterConfig,
) : V2SegmentWriter {
    override val surface: android.view.Surface? get() = null

    override fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        error("Native recording backend is not wired yet: ${config.mimeType} ${config.width}x${config.height} segment=$segmentIndex")
    }

    override fun markAttached(segmentIndex: Int, attachedWallClockMs: Long) = Unit
    override fun requestDrain() = Unit
    override fun finishAndReleaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? = null
    override fun releaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? = null
    override fun currentFile(): File? = null
    override fun segmentWallClockMs(): Long = 0L
    override fun mediaStartWallClockMs(): Long = 0L
    override fun currentSizeBytes(): Long = 0L
}
