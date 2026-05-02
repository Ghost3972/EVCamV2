package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import com.kooo.evcam.v2.nativebridge.GlesNative
import java.io.File

internal enum class V2RecordingBackend {
    AndroidMedia,
    NativeExperimental;

    companion object {
        private const val PREFS_NAME = "v2_recording_backend"
        private const val KEY_NATIVE_EXPERIMENTAL_ENABLED = "native_experimental_enabled"

        fun fromPreferences(context: Context): V2RecordingBackend {
            forceNativeExperimentalDefault(context)
            return NativeExperimental
        }

        fun forceNativeExperimentalDefault(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NATIVE_EXPERIMENTAL_ENABLED, true)
                .apply()
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
    private val handle: Long = if (GlesNative.isLoaded) {
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

    override val backend: V2RecordingBackend = V2RecordingBackend.NativeExperimental
    override val surface: Surface? by lazy {
        if (handle != 0L) GlesNative.nativeSegmentWriterInputSurface(handle) else null
    }

    override fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        check(handle != 0L) { "Native recording backend unavailable: ${config.mimeType} ${config.width}x${config.height}" }
        val finalFile = V2SegmentFileNamer.uniqueFile(
            config.outputDir,
            V2SegmentFileNamer.timestamp(segmentWallClockMs),
            config.fileSuffix,
        )
        val recordingFile = File(finalFile.parentFile, finalFile.name + ".recording")
        check(GlesNative.nativeSegmentWriterStartSegment(handle, recordingFile.absolutePath, segmentIndex, segmentWallClockMs)) {
            "Native recording backend start failed: ${GlesNative.getLastError()}"
        }
        currentFile = finalFile
        tempFile = recordingFile
        this.segmentWallClockMs = segmentWallClockMs
        segmentStartedElapsedMs = SystemClock.elapsedRealtime()
        return finalFile
    }

    override fun markAttached(segmentIndex: Int, attachedWallClockMs: Long) = Unit
    override fun requestDrain() {
        if (handle == 0L) return
        val drained = GlesNative.nativeSegmentWriterDrain(handle, 0L)
        check(drained >= 0L) { "Native recording drain failed: ${GlesNative.getLastError()}" }
        if (drained > 0L) {
            config.metrics.encodedSamples += drained
            if (config.metrics.firstSampleLatencyMs < 0L) {
                config.metrics.firstSampleLatencyMs = SystemClock.elapsedRealtime() - segmentStartedElapsedMs
            }
        }
    }

    override fun finishAndReleaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? {
        if (handle == 0L) return null
        GlesNative.nativeSegmentWriterStop(handle)
        GlesNative.nativeSegmentWriterRelease(handle)
        val finalFile = currentFile ?: return null
        val recordingFile = tempFile ?: finalFile
        return if (recordingFile.exists() && recordingFile.length() > 0L) {
            if (recordingFile == finalFile || recordingFile.renameTo(finalFile)) finalFile else recordingFile
        } else {
            if (recordingFile.exists()) recordingFile.delete()
            null
        }
    }

    override fun releaseBlocking(generateThumbnail: Boolean, timeoutMs: Long): File? {
        if (handle != 0L) GlesNative.nativeSegmentWriterRelease(handle)
        tempFile?.takeIf { it.exists() }?.delete()
        return null
    }

    override fun currentFile(): File? = currentFile
    override fun segmentWallClockMs(): Long = segmentWallClockMs
    override fun mediaStartWallClockMs(): Long = segmentWallClockMs
    override fun currentSizeBytes(): Long = tempFile?.length() ?: currentFile?.length() ?: 0L
}
