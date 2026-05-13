package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
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
    fun load(side: String): V2BlindSpotSmallWindowTransform {
        val app = context.applicationContext
        val rotationDegrees = fixedRotationForSide(side)
        val correction = if (V2BlindSpotSettings.isCorrectionEnabled(app)) {
            V2BlindSpotSettings.correction(app, side).copy(rotation = 0f)
        } else {
            V2BlindSpotCorrection()
        }
        V2AppLog.i(
            TAG,
            "load master blind spot transform side=$side rotation=$rotationDegrees " +
                "correctionRotationIgnored=true systemDefaultWindow=true"
        )
        return V2BlindSpotSmallWindowTransform(rotationDegrees, correction)
    }

    internal companion object {
        private const val TAG = "V2BlindSpotSmallWindow"

        fun fixedRotationForSide(side: String): Int = if (side == "right") 90 else 270
    }
}
