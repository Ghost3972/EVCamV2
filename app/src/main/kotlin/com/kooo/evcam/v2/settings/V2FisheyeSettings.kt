package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT

object V2FisheyeSettings {
    private const val PREFS = "evcam_v2_fisheye_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_K1 = "k1_"
    private const val KEY_K2 = "k2_"
    private const val KEY_ZOOM = "zoom_"
    private const val KEY_CENTER_X = "center_x_"
    private const val KEY_CENTER_Y = "center_y_"

    const val DEFAULT_K1 = V2FisheyeParams.DEFAULT_K1
    const val DEFAULT_K2 = V2FisheyeParams.DEFAULT_K2
    const val DEFAULT_ZOOM = V2FisheyeParams.DEFAULT_ZOOM
    const val DEFAULT_CENTER_X = V2FisheyeParams.DEFAULT_CENTER_X
    const val DEFAULT_CENTER_Y = V2FisheyeParams.DEFAULT_CENTER_Y

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        V2AppLog.i("V2FisheyeSettings", "enabled=$enabled params=${paramsSummary()}")
    }

    fun paramsForIndex(context: Context, index: Int): V2FisheyeParams {
        val defaults = defaultParamsForIndex(index)
        val prefs = prefs(context)
        return defaults.copy(
            k1 = prefs.getFloat(KEY_K1 + index, defaults.k1),
            k2 = prefs.getFloat(KEY_K2 + index, defaults.k2),
            zoom = prefs.getFloat(KEY_ZOOM + index, defaults.zoom),
            centerX = prefs.getFloat(KEY_CENTER_X + index, defaults.centerX),
            centerY = prefs.getFloat(KEY_CENTER_Y + index, defaults.centerY)
        )
    }

    fun defaultParamsForIndex(index: Int): V2FisheyeParams = V2FisheyeParams.defaultForIndex(index)

    fun setParams(context: Context, index: Int, k1: Float, k2: Float, zoom: Float, centerX: Float = DEFAULT_CENTER_X, centerY: Float = DEFAULT_CENTER_Y) {
        val label = defaultParamsForIndex(index).label
        prefs(context).edit()
            .putFloat(KEY_K1 + index, k1)
            .putFloat(KEY_K2 + index, k2)
            .putFloat(KEY_ZOOM + index, zoom.coerceAtLeast(0.1f))
            .putFloat(KEY_CENTER_X + index, centerX)
            .putFloat(KEY_CENTER_Y + index, centerY)
            .apply()
        V2AppLog.i("V2FisheyeSettings", "params index=$index label=$label k1=$k1 k2=$k2 zoom=$zoom center=$centerX,$centerY")
    }

    fun resetAllParams(context: Context) {
        val editor = prefs(context).edit()
        repeat(V2_CAMERA_SLOT_COUNT) { index ->
            editor
                .remove(KEY_K1 + index)
                .remove(KEY_K2 + index)
                .remove(KEY_ZOOM + index)
                .remove(KEY_CENTER_X + index)
                .remove(KEY_CENTER_Y + index)
        }
        editor.apply()
        V2AppLog.i("V2FisheyeSettings", "reset all params defaults=${paramsSummary()}")
    }

    fun paramsSummary(context: Context? = null): String = if (context == null) {
        V2FisheyeParams.defaultSummary()
    } else {
        V2SettingsFormatter.fisheyeParamsSummary(context)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
