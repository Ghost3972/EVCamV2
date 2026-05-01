package com.kooo.evcam.v2.service

import android.content.Context
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.V2NativeCompositor
import com.kooo.evcam.v2.settings.V2FisheyeSettings

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
    ): Boolean {
        if (!compositor.isAvailable) return false

        val enabled = V2FisheyeSettings.isEnabled(context)
        val params = slots.map { V2FisheyeSettings.paramsForIndex(context, it.index) }
        val ok = compositor.configureRuntime(
            recordingSize.width,
            recordingSize.height,
            previewMaxFps,
            recordingFps,
            sideLeftRotation,
            sideRightRotation,
            layoutMode,
            BooleanArray(4) { enabled },
            FloatArray(4) { params.getOrNull(it)?.k1 ?: V2FisheyeSettings.DEFAULT_K1 },
            FloatArray(4) { params.getOrNull(it)?.k2 ?: V2FisheyeSettings.DEFAULT_K2 },
            FloatArray(4) { params.getOrNull(it)?.zoom ?: V2FisheyeSettings.DEFAULT_ZOOM },
            FloatArray(4) { params.getOrNull(it)?.centerX ?: V2FisheyeSettings.DEFAULT_CENTER_X },
            FloatArray(4) { params.getOrNull(it)?.centerY ?: V2FisheyeSettings.DEFAULT_CENTER_Y },
        )
        val summary = slots.joinToString { slot ->
            val p = params.getOrNull(slot.index) ?: V2FisheyeSettings.defaultParamsForIndex(slot.index)
            "${slot.label}:${p.k1}/${p.k2}/${p.zoom}"
        }
        V2AppLog.i(
            "V2CameraEngine",
            "$logPrefix runtimeConfig ok=$ok previewFps=$previewMaxFps encoderFps=$recordingFps fisheye=$enabled perCamera=$summary"
        )
        return ok
    }
}
