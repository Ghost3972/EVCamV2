package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2BlindSpotSettings {
    private const val PREFS = "evcam_v2_blind_spot_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TURN_SIGNAL_PROP_ID = "turn_signal_prop_id"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_WIDTH = "overlay_width"
    private const val KEY_OVERLAY_HEIGHT = "overlay_height"
    private const val KEY_OVERLAY_PREFIX = "overlay_"
    private const val KEY_OVERLAY_ROTATION = "overlay_rotation"
    private const val KEY_OVERLAY_ROTATION_LEFT = "overlay_rotation_left"
    private const val KEY_OVERLAY_ROTATION_RIGHT = "overlay_rotation_right"
    private const val KEY_CORRECTION_ENABLED = "blind_spot_correction_enabled"

    const val DEFAULT_TURN_SIGNAL_PROP_ID = 289408008
    const val LEFT_VALUE = 1
    const val RIGHT_VALUE = 2
    const val OFF_VALUE = 0
    const val HIDE_DELAY_MS = 1_000L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        V2AppLog.i("V2BlindSpotSettings", "enabled=$enabled")
    }

    fun turnSignalPropId(context: Context): Int = prefs(context).getInt(KEY_TURN_SIGNAL_PROP_ID, DEFAULT_TURN_SIGNAL_PROP_ID)

    fun setTurnSignalPropId(context: Context, propId: Int) {
        prefs(context).edit().putInt(KEY_TURN_SIGNAL_PROP_ID, propId).apply()
        V2AppLog.i("V2BlindSpotSettings", "turnSignalPropId=$propId")
    }

    fun overlayX(context: Context, defaultValue: Int): Int = prefs(context).getInt(KEY_OVERLAY_X, defaultValue)

    fun overlayY(context: Context, defaultValue: Int): Int = prefs(context).getInt(KEY_OVERLAY_Y, defaultValue)

    fun overlayWidth(context: Context, defaultValue: Int): Int = prefs(context).getInt(KEY_OVERLAY_WIDTH, defaultValue)

    fun overlayHeight(context: Context, defaultValue: Int): Int = prefs(context).getInt(KEY_OVERLAY_HEIGHT, defaultValue)

    fun overlayX(context: Context, side: String, defaultValue: Int): Int =
        prefs(context).getInt(overlayKey(side, "x"), overlayX(context, defaultValue))

    fun overlayY(context: Context, side: String, defaultValue: Int): Int =
        prefs(context).getInt(overlayKey(side, "y"), overlayY(context, defaultValue))

    fun overlayWidth(context: Context, side: String, defaultValue: Int): Int =
        prefs(context).getInt(overlayKey(side, "width"), overlayWidth(context, defaultValue))

    fun overlayHeight(context: Context, side: String, defaultValue: Int): Int =
        prefs(context).getInt(overlayKey(side, "height"), overlayHeight(context, defaultValue))

    fun overlayRotation(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_ROTATION, 0)

    fun overlayRotation(context: Context, side: String): Int {
        val key = overlayRotationKey(side)
        return prefs(context).getInt(key, overlayRotation(context))
    }

    data class Correction(
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val rotation: Float = 0f,
        val mirrorH: Boolean = false,
        val mirrorV: Boolean = false,
    )

    val DEFAULT_CORRECTION = Correction()

    fun isCorrectionEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CORRECTION_ENABLED, false)

    fun setCorrectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CORRECTION_ENABLED, enabled).apply()
        V2AppLog.i("V2BlindSpotSettings", "correctionEnabled=$enabled")
    }

    fun correction(context: Context, side: String): Correction = Correction(
        scaleX = prefs(context).getFloat(correctionKey(side, "scale_x"), 1f),
        scaleY = prefs(context).getFloat(correctionKey(side, "scale_y"), 1f),
        translateX = prefs(context).getFloat(correctionKey(side, "translate_x"), 0f),
        translateY = prefs(context).getFloat(correctionKey(side, "translate_y"), 0f),
        rotation = prefs(context).getFloat(correctionKey(side, "rotation"), 0f),
        mirrorH = prefs(context).getBoolean(correctionKey(side, "mirror_h"), false),
        mirrorV = prefs(context).getBoolean(correctionKey(side, "mirror_v"), false),
    )

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
        V2AppLog.i("V2BlindSpotSettings", "overlayPosition=$x,$y")
    }

    fun setOverlayBounds(context: Context, x: Int, y: Int, width: Int, height: Int) {
        prefs(context).edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .putInt(KEY_OVERLAY_WIDTH, width)
            .putInt(KEY_OVERLAY_HEIGHT, height)
            .apply()
        V2AppLog.i("V2BlindSpotSettings", "overlayBounds=$x,$y ${width}x$height")
    }

    fun setOverlayBounds(context: Context, side: String, x: Int, y: Int, width: Int, height: Int) {
        prefs(context).edit()
            .putInt(overlayKey(side, "x"), x)
            .putInt(overlayKey(side, "y"), y)
            .putInt(overlayKey(side, "width"), width)
            .putInt(overlayKey(side, "height"), height)
            .apply()
        V2AppLog.i("V2BlindSpotSettings", "overlayBounds side=$side $x,$y ${width}x$height")
    }

    fun setOverlayRotation(context: Context, rotation: Int) {
        val normalized = ((rotation % 360) + 360) % 360
        prefs(context).edit().putInt(KEY_OVERLAY_ROTATION, normalized).apply()
        V2AppLog.i("V2BlindSpotSettings", "overlayRotation=$normalized")
    }

    fun setOverlayRotation(context: Context, side: String, rotation: Int) {
        val normalized = ((rotation % 360) + 360) % 360
        prefs(context).edit().putInt(overlayRotationKey(side), normalized).apply()
        V2AppLog.i("V2BlindSpotSettings", "overlayRotation side=$side value=$normalized")
    }

    fun setCorrection(context: Context, side: String, correction: Correction) {
        prefs(context).edit()
            .putFloat(correctionKey(side, "scale_x"), correction.scaleX.coerceIn(0.5f, 2f))
            .putFloat(correctionKey(side, "scale_y"), correction.scaleY.coerceIn(0.5f, 2f))
            .putFloat(correctionKey(side, "translate_x"), correction.translateX.coerceIn(-1f, 1f))
            .putFloat(correctionKey(side, "translate_y"), correction.translateY.coerceIn(-1f, 1f))
            .putFloat(correctionKey(side, "rotation"), normalizeRotation(correction.rotation))
            .putBoolean(correctionKey(side, "mirror_h"), correction.mirrorH)
            .putBoolean(correctionKey(side, "mirror_v"), correction.mirrorV)
            .apply()
        V2AppLog.i("V2BlindSpotSettings", "correction side=$side value=$correction")
    }

    fun resetCorrection(context: Context, side: String) {
        setCorrection(context, side, DEFAULT_CORRECTION)
    }

    fun resetAllCorrections(context: Context) {
        resetCorrection(context, "left")
        resetCorrection(context, "right")
        V2AppLog.i("V2BlindSpotSettings", "reset all correction params")
    }

    private fun overlayRotationKey(side: String): String =
        if (side == "right") KEY_OVERLAY_ROTATION_RIGHT else KEY_OVERLAY_ROTATION_LEFT

    private fun overlayKey(side: String, name: String): String =
        KEY_OVERLAY_PREFIX + if (side == "right") "right_$name" else "left_$name"

    private fun correctionKey(side: String, name: String): String = "blind_spot_correction_${side}_$name"

    private fun normalizeRotation(rotation: Float): Float = ((rotation % 360f) + 360f) % 360f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
