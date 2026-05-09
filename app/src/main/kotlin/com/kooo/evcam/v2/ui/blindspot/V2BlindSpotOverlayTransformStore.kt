package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
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
            rotationDegrees = 0,
            correction = config.correction,
        )
    }
}
