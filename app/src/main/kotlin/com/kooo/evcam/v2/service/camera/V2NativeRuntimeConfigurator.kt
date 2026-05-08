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
        val blindSpotEnabled = fisheyeConfig.blindSpotEnabled
        val blindSpotParams = List(V2_CAMERA_SLOT_COUNT) { fisheyeConfig.blindSpotParamsForIndex(it) }
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
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k3 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k4 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].zoom },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerX },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerY },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].fx },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].fy },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].sourceWidth },
            FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].sourceHeight },
            BooleanArray(V2_CAMERA_SLOT_COUNT) { blindSpotEnabled },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].k1 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].k2 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].k3 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].k4 },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].zoom },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].centerX },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].centerY },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].fx },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].fy },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].sourceWidth },
            FloatArray(V2_CAMERA_SLOT_COUNT) { blindSpotParams[it].sourceHeight },
        )
        val summary = slots.joinToString { slot ->
            val p = fisheyeConfig.paramsForIndex(slot.index)
            "${slot.label}:${p.k1}/${p.k2}/${p.k3}/${p.k4}/${p.zoom}/${p.centerX}/${p.centerY}/${p.fx}/${p.fy}/${p.sourceWidth}x${p.sourceHeight}"
        }
        val blindSpotSummary = slots.joinToString { slot ->
            val p = fisheyeConfig.blindSpotParamsForIndex(slot.index)
            "${slot.label}:${p.k1}/${p.k2}/${p.k3}/${p.k4}/${p.zoom}/${p.centerX}/${p.centerY}/${p.fx}/${p.fy}/${p.sourceWidth}x${p.sourceHeight}"
        }
        V2AppLog.i(
            "V2CameraEngine",
            "$logPrefix runtimeConfig ok=$ok previewFps=$previewMaxFps encoderFps=$recordingFps fisheye=$enabled perCamera=$summary blindSpotFisheye=$blindSpotEnabled blindSpot=$blindSpotSummary"
        )
        return ok
    }
}
