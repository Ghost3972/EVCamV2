package com.kooo.evcam.v2.service.camera

import android.util.Size
import com.kooo.evcam.v2.recording.RecordingMetrics
import com.kooo.evcam.v2.service.V2CameraHealthSnapshot
import com.kooo.evcam.v2.service.V2CameraSlotHealth

internal data class V2CameraSlotState(
    val index: Int,
    val label: String,
    val cameraId: String,
    val inputSizeLabel: String,
    val deviceOpen: Boolean,
    val sessionOpen: Boolean,
    val inputReady: Boolean,
    val previewAttached: Boolean,
    val frameSignals: Long,
    val renderedFrames: Long,
    val renderFailures: Long,
    val lastRenderMs: Long,
    val lastPreviewError: String,
    val nativeFrameGeneration: Long,
    val nativeLatchedGeneration: Long,
    val nativePreviewGeneration: Long,
    val nativeEncoderGeneration: Long,
    val nativeHasLatchedFrame: Boolean,
    val nativeInputDirty: Boolean,
    val nativeInputAttached: Boolean,
    val nativeInputUpdates: Long,
)

internal object V2CameraEngineStateMapper {
    fun slotState(
        index: Int,
        label: String,
        cameraId: String,
        inputSize: Size?,
        fallbackSize: Size,
        deviceOpen: Boolean,
        sessionOpen: Boolean,
        inputReady: Boolean,
        previewAttached: Boolean,
        frameSignals: Long,
        renderedFrames: Long,
        renderFailures: Long,
        lastRenderMs: Long,
        lastPreviewError: String,
        nativeFrameGeneration: Long,
        nativeLatchedGeneration: Long,
        nativePreviewGeneration: Long,
        nativeEncoderGeneration: Long,
        nativeHasLatchedFrame: Boolean,
        nativeInputDirty: Boolean,
        nativeInputAttached: Boolean,
        nativeInputUpdates: Long,
    ): V2CameraSlotState = V2CameraSlotState(
        index = index,
        label = label,
        cameraId = cameraId,
        inputSizeLabel = inputSizeLabel(inputSize, fallbackSize),
        deviceOpen = deviceOpen,
        sessionOpen = sessionOpen,
        inputReady = inputReady,
        previewAttached = previewAttached,
        frameSignals = frameSignals,
        renderedFrames = renderedFrames,
        renderFailures = renderFailures,
        lastRenderMs = lastRenderMs,
        lastPreviewError = lastPreviewError,
        nativeFrameGeneration = nativeFrameGeneration,
        nativeLatchedGeneration = nativeLatchedGeneration,
        nativePreviewGeneration = nativePreviewGeneration,
        nativeEncoderGeneration = nativeEncoderGeneration,
        nativeHasLatchedFrame = nativeHasLatchedFrame,
        nativeInputDirty = nativeInputDirty,
        nativeInputAttached = nativeInputAttached,
        nativeInputUpdates = nativeInputUpdates,
    )

    fun healthSnapshot(
        cameraAccessAllowed: Boolean,
        released: Boolean,
        recording: Boolean,
        slots: List<V2CameraSlotState>,
        recordingMetrics: RecordingMetrics?,
    ): V2CameraHealthSnapshot = V2CameraHealthSnapshot(
        cameraAccessAllowed = cameraAccessAllowed,
        released = released,
        recording = recording,
        slots = slots.map { it.toHealth() },
        recordingMetrics = recordingMetrics,
    )

    fun statusSlots(slots: List<V2CameraSlotState>): List<V2CameraStatusFormatter.SlotStatus> {
        return slots.map { slot ->
            V2CameraStatusFormatter.SlotStatus(
                index = slot.index,
                label = slot.label,
                inputSizeLabel = slot.inputSizeLabel,
                frameSignals = slot.frameSignals,
                renderedFrames = slot.renderedFrames,
                renderFailures = slot.renderFailures,
                lastPreviewError = slot.lastPreviewError,
                lastRenderMs = slot.lastRenderMs,
            )
        }
    }

    private fun V2CameraSlotState.toHealth(): V2CameraSlotHealth = V2CameraSlotHealth(
        index = index,
        label = label,
        cameraId = cameraId,
        deviceOpen = deviceOpen,
        sessionOpen = sessionOpen,
        inputReady = inputReady,
        previewAttached = previewAttached,
        frameSignals = frameSignals,
        renderedFrames = renderedFrames,
        renderFailures = renderFailures,
        lastRenderMs = lastRenderMs,
        lastError = lastPreviewError,
        nativeFrameGeneration = nativeFrameGeneration,
        nativeLatchedGeneration = nativeLatchedGeneration,
        nativePreviewGeneration = nativePreviewGeneration,
        nativeEncoderGeneration = nativeEncoderGeneration,
        nativeHasLatchedFrame = nativeHasLatchedFrame,
        nativeInputDirty = nativeInputDirty,
        nativeInputAttached = nativeInputAttached,
        nativeInputUpdates = nativeInputUpdates,
    )

    private fun inputSizeLabel(inputSize: Size?, fallbackSize: Size): String {
        val size = inputSize ?: fallbackSize
        return "${size.width}x${size.height}"
    }
}
