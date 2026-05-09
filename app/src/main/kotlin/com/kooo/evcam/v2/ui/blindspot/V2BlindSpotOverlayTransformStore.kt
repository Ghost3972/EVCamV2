package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal data class V2BlindSpotOverlayTransform(
    val rotationDegrees: Int = 0,
    val correction: V2BlindSpotCorrection = V2BlindSpotCorrection(),
)

internal class V2BlindSpotOverlayTransformStore(
    private val context: Context,
) {
    fun load(side: String): V2BlindSpotOverlayTransform {
        val config = V2SettingsRepository.blindSpotOverlayConfig(context, side, 0, 0, 0, 0)
        return V2BlindSpotOverlayTransform(
            rotationDegrees = config.rotation,
            correction = config.correction,
        )
    }

    fun rotateClockwise(side: String, currentRotation: Int): V2BlindSpotOverlayTransform {
        val nextRotation = (currentRotation + 90) % 360
        V2BlindSpotSettings.setOverlayRotation(context, side, nextRotation)
        return load(side).copy(rotationDegrees = nextRotation)
    }
}
