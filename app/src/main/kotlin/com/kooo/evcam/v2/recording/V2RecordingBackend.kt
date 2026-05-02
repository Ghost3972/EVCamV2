package com.kooo.evcam.v2.recording

import android.content.Context
import android.view.Surface
import com.kooo.evcam.v2.nativebridge.VulkanNative
import java.io.File

internal enum class V2RecordingBackend {
    AndroidMedia,
    NativeExperimental;

    companion object {
        private const val PREFS_NAME = "v2_recording_backend"
        private const val KEY_NATIVE_EXPERIMENTAL_ENABLED = "native_experimental_enabled"

        fun fromPreferences(context: Context): V2RecordingBackend {
            val enabled = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NATIVE_EXPERIMENTAL_ENABLED, false)
            return if (enabled) NativeExperimental else AndroidMedia
        }
    }
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
        V2RecordingBackend.NativeExperimental -> V2NativeSegmentWriter(config)
    }
}

private class V2NativeSegmentWriter(
    private val config: V2SegmentWriterConfig,
) : V2SegmentWriter {
    private val handle: Long = if (VulkanNative.isLoaded) {
        VulkanNative.createNativeSegmentWriter(
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

    override val surface: Surface? by lazy {
        if (handle != 0L) VulkanNative.nativeSegmentWriterInputSurface(handle) else null
    }

    override fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        check(handle != 0L) { "Native recording backend unavailable: ${config.mimeType} ${config.width}x${config.height}" }
        val finalFile = V2SegmentFileNamer.uniqueFile(
            config.outputDir,
            V2SegmentFileNamer.timestamp(segmentWallClockMs),
            config.fileSuffix,
        )
        val recordingFile = File(finalFile.parentFile, finalFile.name + ".recording")
        check(VulkanNative.nativeSegmentWriterStartSegment(handle, recordingFile.absolutePath, segmentIndex, segmentWallClockMs)) {
            "Native recording backend start failed: ${VulkanNative.getLastError()}"
        }
        currentFile = finalFile
        tempFile = recordingFile
        this.segmentWallClockMs = segmentWallClockMs
        return finalFile
    }

    override fun markAttached(segmentIndex: Int, attachedWallClockMs: Long) = Unit
    override fun requestDrain() {
        if (handle != 0L) VulkanNative.nativeSegmentWriterDrain(handle, 0L)
    }

    override fun finishAndReleaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? {
        if (handle == 0L) return null
        VulkanNative.nativeSegmentWriterStop(handle)
        VulkanNative.nativeSegmentWriterRelease(handle)
        val finalFile = currentFile ?: return null
        val recordingFile = tempFile ?: finalFile
        return if (recordingFile.exists() && recordingFile.length() > 0L) {
            if (recordingFile == finalFile || recordingFile.renameTo(finalFile)) finalFile else recordingFile
        } else {
            null
        }
    }

    override fun releaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? {
        if (handle != 0L) VulkanNative.nativeSegmentWriterRelease(handle)
        return null
    }

    override fun currentFile(): File? = currentFile
    override fun segmentWallClockMs(): Long = segmentWallClockMs
    override fun mediaStartWallClockMs(): Long = segmentWallClockMs
    override fun currentSizeBytes(): Long = tempFile?.length() ?: currentFile?.length() ?: 0L
}
