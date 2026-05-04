package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

object V2NativeRuntimeConfigurator {
    data class SlotConfig(val index: Int, val label: String)

    fun configure(
        context: Context,
        compositor: V2NativeCompositor,
        recordingSize: Size,
        recordingFps: Int,
        previewMaxFps: Int,
        sideLeftRotation: Int,
        sideRightRotation: Int,
        layoutMode: Int,
        slots: List<SlotConfig>,
        logPrefix: String,
        fisheye: V2SettingsSnapshot.Fisheye? = null,
    ): Boolean {
        if (!compositor.isAvailable) return false

        val fisheyeConfig = fisheye ?: V2SettingsRepository.fisheyeConfig(context)
        val enabled = fisheyeConfig.enabled
        val params = List(V2_CAMERA_SLOT_COUNT) { fisheyeConfig.paramsForIndex(it) }
        val ok = compositor.configureRuntime(
            recordingSize.width,
            recordingSize.height,
            previewMaxFps,
            recordingFps,
            sideLeftRotation,
            sideRightRotation,
            layoutMode,
            BooleanArray(V2_CAMERA_SLOT_COUNT) { enabled },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k1 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k2 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].zoom },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerX },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerY },
        )
        val summary = slots.joinToString { slot ->
            val p = fisheyeConfig.paramsForIndex(slot.index)
            "${slot.label}:${p.k1}/${p.k2}/${p.zoom}"
        }
        V2AppLog.i(
            "V2CameraEngine",
            "$logPrefix runtimeConfig ok=$ok previewFps=$previewMaxFps encoderFps=$recordingFps fisheye=$enabled perCamera=$summary"
        )
        return ok
    }
}
