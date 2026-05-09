package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.content.pm.ActivityInfo
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2BlindSpotSettings

internal data class V2BlindSpotSmallWindowTransform(
    val rotationDegrees: Int,
    val correction: V2BlindSpotCorrection,
)

internal class V2BlindSpotSmallWindowTransformStore(
    private val context: Context,
) {
    fun requestedOrientation(): Int {
        return if (windowOrientation() == V2BlindSpotSettings.WINDOW_ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun load(side: String): V2BlindSpotSmallWindowTransform {
        val app = context.applicationContext
        val orientation = windowOrientation()
        val windowDefaultRotation = windowDefaultRotationForSide(orientation, side)
        val savedOverlayRotation = V2BlindSpotSettings.overlayRotation(app, side)
        val rotationDegrees = normalizeDegrees(windowDefaultRotation + savedOverlayRotation)
        val correction = if (V2BlindSpotSettings.isCorrectionEnabled(app)) {
            V2BlindSpotSettings.correction(app, side)
        } else {
            V2BlindSpotCorrection()
        }
        V2AppLog.i(
            TAG,
            "load master blind spot transform side=$side rotation=$rotationDegrees " +
                "defaultRotation=$windowDefaultRotation savedRotation=$savedOverlayRotation " +
                "correctionRotation=${correction.rotation} windowOrientation=$orientation systemDefaultWindow=true"
        )
        return V2BlindSpotSmallWindowTransform(rotationDegrees, correction)
    }

    private fun windowOrientation(): String = V2BlindSpotSettings.windowOrientation(context.applicationContext)

    private fun windowDefaultRotationForSide(orientation: String, side: String): Int {
        if (orientation != V2BlindSpotSettings.WINDOW_ORIENTATION_LANDSCAPE) return 0
        return if (side == "right") 270 else 90
    }

    private fun normalizeDegrees(value: Int): Int = ((value % 360) + 360) % 360

    private companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
    }
}
