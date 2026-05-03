package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Handler
import java.io.File

object V2RecordingPipelineFactory {
    data class CameraTarget(
        val cameraHandle: Long,
        val label: String,
        val width: Int,
        val height: Int,
    )

    data class Config(
        val outputDir: File,
        val nativeHandle: Long,
        val renderHandler: Handler,
        val outputWidth: Int,
        val outputHeight: Int,
        val videoBitrate: Int,
        val recordingFps: Int,
        val segmentDurationMs: Long,
        val fileSuffix: String = "",
        val cameraTargets: List<CameraTarget> = emptyList(),
    )

    fun create(
        context: Context,
        config: Config,
        onFailure: (String) -> Unit,
    ): V2RecordingPipeline =
        V2CompositeRecorder(
            context = context,
            outputDir = config.outputDir,
            nativeHandle = config.nativeHandle,
            renderHandler = config.renderHandler,
            outputWidth = config.outputWidth,
            outputHeight = config.outputHeight,
            videoBitrate = config.videoBitrate,
            recordingFps = config.recordingFps,
            segmentDurationMs = config.segmentDurationMs,
            fileSuffix = config.fileSuffix,
            onFailure = onFailure,
        )
}
