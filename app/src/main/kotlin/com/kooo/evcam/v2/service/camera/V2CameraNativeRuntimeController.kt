package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2CameraNativeRuntimeController(
    private val context: Context,
    private val nativeCompositor: V2NativeCompositor,
    private val pipelineHandle: Long,
    private val recordingSize: Size,
    private val recordingFps: Int,
    private val previewMaxFps: Int,
    private val slots: List<V2CameraSlot>,
) {
    fun configure(logPrefix: String, fisheye: V2SettingsSnapshot.Fisheye? = null) {
        if (pipelineHandle == 0L) return
        V2NativeRuntimeConfigurator.configure(
            context = context,
            compositor = nativeCompositor,
            recordingSize = recordingSize,
            recordingFps = recordingFps,
            previewMaxFps = previewMaxFps,
            sideLeftRotation = SIDE_LEFT_ROTATION,
            sideRightRotation = SIDE_RIGHT_ROTATION,
            layoutMode = DEFAULT_LAYOUT_MODE,
            slots = slots.map { slot -> V2NativeRuntimeConfigurator.SlotConfig(slot.index, slot.spec.label) },
            logPrefix = logPrefix,
            fisheye = fisheye,
        )
    }

    private companion object {
        private const val SIDE_LEFT_ROTATION = 270
        private const val SIDE_RIGHT_ROTATION = 90
        private const val DEFAULT_LAYOUT_MODE = 0
    }
}
