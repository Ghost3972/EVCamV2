package com.kooo.evcam.v2.service.camera

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.settings.V2FisheyeParams
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
        val runtimeConfig = V2NativeCompositor.RuntimeConfig(
            width = recordingSize.width,
            height = recordingSize.height,
            previewFps = previewMaxFps,
            encoderFps = recordingFps,
            sideLeftRotation = sideLeftRotation,
            sideRightRotation = sideRightRotation,
            layoutMode = layoutMode,
            fisheye = buildFisheyeRuntimeArrays(enabled, params),
            blindSpotFisheye = buildFisheyeRuntimeArrays(blindSpotEnabled, blindSpotParams),
        )
        val ok = compositor.configureRuntime(runtimeConfig)
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

    private fun buildFisheyeRuntimeArrays(
        enabled: Boolean,
        params: List<V2FisheyeParams>,
    ) = V2NativeCompositor.FisheyeRuntimeArrays(
        enabled = BooleanArray(V2_CAMERA_SLOT_COUNT) { enabled },
        k1 = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k1 },
        k2 = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k2 },
        k3 = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k3 },
        k4 = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].k4 },
        zoom = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].zoom },
        centerX = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerX },
        centerY = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].centerY },
        fx = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].fx },
        fy = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].fy },
        sourceWidth = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].sourceWidth },
        sourceHeight = FloatArray(V2_CAMERA_SLOT_COUNT) { params[it].sourceHeight },
    )
}
