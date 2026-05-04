package com.kooo.evcam.v2.service.camera

import android.util.Size
import com.kooo.evcam.v2.nativebridge.GlesNative
import com.kooo.evcam.v2.nativebridge.NativeMetricsSnapshot
import com.kooo.evcam.v2.service.V2CameraHealthSnapshot

internal class V2CameraEngineStatusController(
    private val slots: List<V2CameraSlot>,
    private val pipelineHandle: Long,
    private val fallbackInputSize: Size,
    private val compositeOutputSize: Size,
    private val statusFormatter: V2CameraStatusFormatter,
    private val recordingController: V2CameraRecordingController,
    private val cameraAccessAllowed: () -> Boolean,
    private val released: () -> Boolean,
) {
    fun previewIndexForPosition(position: String): Int? {
        return slots.firstOrNull { it.spec.name == position }?.index
    }

    fun previewDescription(index: Int): String {
        val slot = slots.getOrNull(index) ?: return "unknown"
        return "${slot.spec.name}/${slot.spec.label}/cameraId=${slot.spec.cameraId}/slot=$index"
    }

    fun previewRenderedFrames(index: Int): Long {
        val localFrames = slots.getOrNull(index)?.renderedFrames ?: 0L
        val nativeFrames = nativeSlotMetric(index, NativeMetricsSnapshot.SLOT_PREVIEW_RENDERS)
        return maxOf(localFrames, nativeFrames)
    }

    fun previewInputSizeLabel(index: Int): String {
        val size = slots.getOrNull(index)?.inputSize ?: fallbackInputSize
        return "${size.width}×${size.height}"
    }

    fun compositePreviewSizeLabel(): String = "${compositeOutputSize.width}×${compositeOutputSize.height}"

    fun previewInputSize(index: Int): Size? = slots.getOrNull(index)?.inputSize ?: fallbackInputSize

    fun healthSnapshot(): V2CameraHealthSnapshot = V2CameraEngineStateMapper.healthSnapshot(
        cameraAccessAllowed = cameraAccessAllowed(),
        released = released(),
        recording = recordingController.isRecording,
        slots = slotStates(),
        recordingMetrics = recordingController.metricsSnapshot(),
    )

    fun statusText(): String = statusFormatter.status(
        recording = recordingController.isNormalRecording,
        recordingStartedAtMs = recordingController.startedAtMs,
        metrics = recordingController.metricsSnapshot(),
        slots = V2CameraEngineStateMapper.statusSlots(slotStates()),
    )

    private fun slotStates(): List<V2CameraSlotState> {
        val metrics = if (pipelineHandle != 0L && GlesNative.isLoaded) {
            runCatching { GlesNative.getMetricsSnapshot(pipelineHandle) }.getOrDefault(longArrayOf())
        } else {
            longArrayOf()
        }
        return slots.map { slot -> slot.toState(metrics) }
    }

    private fun V2CameraSlot.toState(nativeMetrics: LongArray): V2CameraSlotState {
        val nativeBase = NativeMetricsSnapshot.slotBase(index)
        val nativeSignals = nativeMetrics.getOrNull(nativeBase + NativeMetricsSnapshot.SLOT_FRAME_SIGNALS)?.coerceAtLeast(0L) ?: 0L
        val nativeRenders = nativeMetrics.getOrNull(nativeBase + NativeMetricsSnapshot.SLOT_PREVIEW_RENDERS)?.coerceAtLeast(0L) ?: 0L
        val nativeDrops = nativeMetrics.getOrNull(nativeBase + NativeMetricsSnapshot.SLOT_PREVIEW_DROPS)?.coerceAtLeast(0L) ?: 0L
        return V2CameraEngineStateMapper.slotState(
            index = index,
            label = spec.label,
            cameraId = spec.cameraId,
            inputSize = inputSize,
            fallbackSize = fallbackInputSize,
            deviceOpen = nativeCameraHandle != 0L,
            sessionOpen = nativeCameraHandle != 0L,
            inputReady = inputSurface != null,
            previewAttached = previewAttached,
            frameSignals = maxOf(frameSignals, nativeSignals),
            renderedFrames = maxOf(renderedFrames, nativeRenders),
            renderFailures = maxOf(renderFailures, nativeDrops),
            lastRenderMs = lastRenderMs,
            lastPreviewError = lastPreviewError,
        )
    }

    private fun nativeSlotMetric(index: Int, offset: Int): Long {
        if (index !in slots.indices || pipelineHandle == 0L || !GlesNative.isLoaded) return 0L
        val snapshot = runCatching { GlesNative.getMetricsSnapshot(pipelineHandle) }.getOrNull() ?: return 0L
        return snapshot
            .getOrNull(NativeMetricsSnapshot.slotBase(index) + offset)
            ?.coerceAtLeast(0L)
            ?: 0L
    }
}
