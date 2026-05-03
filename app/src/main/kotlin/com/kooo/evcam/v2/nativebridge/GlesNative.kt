package com.kooo.evcam.v2.nativebridge

import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog

object GlesNative {
    val isLoaded: Boolean
    val loadError: Throwable?

    init {
        var error: Throwable? = null
        val loaded = try {
            System.loadLibrary("evcam_gles_compositor")
            true
        } catch (t: Throwable) {
            error = t
            false
        }
        isLoaded = loaded
        loadError = error
        if (loaded) {
            V2AppLog.i("GlesNative", "native library loaded")
        } else {
            V2AppLog.e("GlesNative", "native library load failed", error)
        }
    }

    external fun getGlesSummary(): String
    external fun createCompositor(width: Int, height: Int): Long
    external fun createOesTexture(handle: Long, index: Int): Int
    external fun destroyOesInput(handle: Long, index: Int): Boolean
    external fun attachEncoderSurface(handle: Long, surface: Surface): Boolean
    external fun setCompositorRuntimeConfig(
        handle: Long,
        width: Int,
        height: Int,
        previewFps: Int,
        encoderFps: Int,
        sideLeftRotation: Int,
        sideRightRotation: Int,
        layoutMode: Int,
        fisheyeEnabled: BooleanArray,
        k1: FloatArray,
        k2: FloatArray,
        zoom: FloatArray,
        centerX: FloatArray,
        centerY: FloatArray
    ): Boolean
    external fun setPreviewMaxFps(handle: Long, fps: Int): Boolean
    external fun startPreviewWorker(handle: Long, fps: Int): Boolean
    external fun stopPreviewWorker(handle: Long, timeoutMs: Long): Boolean
    external fun startRecordingSession(handle: Long, fps: Int, segmentDurationMs: Long, wallClockMs: Long): Long
    external fun stopRecordingSession(handle: Long): Boolean
    external fun setRecordingThumbnailPath(handle: Long, path: String): Boolean
    external fun startRecordingWorker(handle: Long, writerHandle: Long, fps: Int): Boolean
    external fun pollRecordingWorker(handle: Long): Long
    external fun resumeRecordingWorker(handle: Long, writerHandle: Long): Boolean
    external fun stopRecordingWorker(handle: Long, timeoutMs: Long): Long
    external fun snapshotRecordingWorker(handle: Long): LongArray
    external fun finalRenderAndDrain(handle: Long, writerHandle: Long, timeoutUs: Long): Long
    external fun beginNextRecordingSegment(handle: Long): Long
    external fun completeRecordingSegmentSwitch(handle: Long, success: Boolean): Boolean
    external fun createNativeSegmentWriter(width: Int, height: Int, fps: Int, bitrate: Int, mimeType: String): Long
    external fun nativeSegmentWriterInputSurface(writerHandle: Long): Surface?
    external fun nativeSegmentWriterStartSegment(writerHandle: Long, path: String, segmentIndex: Int, wallClockMs: Long): Boolean
    external fun nativeSegmentWriterFinish(writerHandle: Long, finalPath: String): Boolean
    external fun nativeSegmentWriterRelease(writerHandle: Long): Boolean
    external fun createNativeCameraPreview(cameraId: String, surface: Surface): Long
    external fun releaseNativeCameraPreview(cameraHandle: Long): Boolean
    external fun nativeExtractEmergencyClip(
        outputPath: String,
        finalOutputPath: String,
        clipStartWallClockMs: Long,
        clipEndWallClockMs: Long,
        sourcePaths: Array<String>,
        sourceStartWallClockMs: LongArray,
        sourceEndWallClockMs: LongArray,
    ): Long
    external fun createOesInput(handle: Long, index: Int, surfaceTexture: android.graphics.SurfaceTexture): Boolean
    external fun attachPreviewSurfaceWithMode(handle: Long, index: Int, surface: Surface, applyFisheye: Boolean, applyNativeTransform: Boolean): Boolean
    external fun detachPreviewSurface(handle: Long, index: Int): Boolean
    external fun detachEncoderSurface(handle: Long): Boolean
    external fun releaseCompositor(handle: Long)
    external fun getMetricsSnapshot(handle: Long): LongArray
    external fun getLastError(): String

    fun summaryOrFallback(): String {
        return if (isLoaded) {
            runCatching { getGlesSummary() }.getOrElse { "GLES native error: ${it.message}" }
        } else {
            "GLES native not loaded: ${loadError?.message ?: "unknown"}"
        }
    }
}
