package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT

object V2FisheyeSettings {
    private const val PREFS = "evcam_v2_fisheye_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_K1 = "k1_"
    private const val KEY_K2 = "k2_"
    private const val KEY_K3 = "k3_"
    private const val KEY_K4 = "k4_"
    private const val KEY_ZOOM = "zoom_"
    private const val KEY_CENTER_X = "center_x_"
    private const val KEY_CENTER_Y = "center_y_"
    private const val KEY_FX = "fx_"
    private const val KEY_FY = "fy_"
    private const val KEY_SOURCE_WIDTH = "source_width_"
    private const val KEY_SOURCE_HEIGHT = "source_height_"
    private const val KEY_BLIND_SPOT_ENABLED = "blind_spot_enabled"
    private const val BLIND_SPOT_PREFIX = "blind_spot_"

    const val DEFAULT_K1 = V2FisheyeParams.DEFAULT_K1
    const val DEFAULT_K2 = V2FisheyeParams.DEFAULT_K2
    const val DEFAULT_K3 = V2FisheyeParams.DEFAULT_K3
    const val DEFAULT_K4 = V2FisheyeParams.DEFAULT_K4
    const val DEFAULT_ZOOM = V2FisheyeParams.DEFAULT_ZOOM
    const val DEFAULT_CENTER_X = V2FisheyeParams.DEFAULT_CENTER_X
    const val DEFAULT_CENTER_Y = V2FisheyeParams.DEFAULT_CENTER_Y
    const val DEFAULT_FX = V2FisheyeParams.DEFAULT_FX
    const val DEFAULT_FY = V2FisheyeParams.DEFAULT_FY
    const val DEFAULT_SOURCE_WIDTH = V2FisheyeParams.DEFAULT_SOURCE_WIDTH
    const val DEFAULT_SOURCE_HEIGHT = V2FisheyeParams.DEFAULT_SOURCE_HEIGHT

    private val AVM_960_PARAMS = listOf(
        V2FisheyeParams(
            label = "前",
            k1 = 0.147177f,
            k2 = -0.0687642f,
            k3 = 0.0210056f,
            k4 = -0.00258953f,
            zoom = 1.65f,
            centerX = 959.241943f / 1920.0f,
            centerY = 765.424133f / 1536.0f,
            fx = 444.355f,
            fy = 444.942f,
            sourceWidth = 1920.0f,
            sourceHeight = 1536.0f,
        ),
        V2FisheyeParams(
            label = "后",
            k1 = 0.148636f,
            k2 = -0.068947f,
            k3 = 0.0208133f,
            k4 = -0.00253161f,
            zoom = 1.65f,
            centerX = 954.304443f / 1920.0f,
            centerY = 765.962280f / 1536.0f,
            fx = 445.043f,
            fy = 445.644f,
            sourceWidth = 1920.0f,
            sourceHeight = 1536.0f,
        ),
        V2FisheyeParams(
            label = "左",
            k1 = 0.1463f,
            k2 = -0.0673148f,
            k3 = 0.0199799f,
            k4 = -0.00235741f,
            zoom = 1.4f,
            centerX = 962.442444f / 1920.0f,
            centerY = 765.679626f / 1536.0f,
            fx = 445.762f,
            fy = 445.645f,
            sourceWidth = 1920.0f,
            sourceHeight = 1536.0f,
        ),
        V2FisheyeParams(
            label = "右",
            k1 = 0.146899f,
            k2 = -0.0681913f,
            k3 = 0.0202973f,
            k4 = -0.00245689f,
            zoom = 1.4f,
            centerX = 961.560730f / 1920.0f,
            centerY = 763.426758f / 1536.0f,
            fx = 444.029f,
            fy = 444.427f,
            sourceWidth = 1920.0f,
            sourceHeight = 1536.0f,
        ),
    )

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun isBlindSpotEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BLIND_SPOT_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        V2AppLog.i("V2FisheyeSettings", "enabled=$enabled params=${paramsSummary()}")
    }

    fun setBlindSpotEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLIND_SPOT_ENABLED, enabled).apply()
        V2AppLog.i("V2FisheyeSettings", "blindSpotEnabled=$enabled params=${paramsSummary(context)}")
    }

    fun paramsForIndex(context: Context, index: Int): V2FisheyeParams {
        val defaults = defaultParamsForIndex(index)
        val prefs = prefs(context)
        return defaults.copy(
            k1 = prefs.getFloat(KEY_K1 + index, defaults.k1),
            k2 = prefs.getFloat(KEY_K2 + index, defaults.k2),
            k3 = prefs.getFloat(KEY_K3 + index, defaults.k3),
            k4 = prefs.getFloat(KEY_K4 + index, defaults.k4),
            zoom = prefs.getFloat(KEY_ZOOM + index, defaults.zoom),
            centerX = prefs.getFloat(KEY_CENTER_X + index, defaults.centerX),
            centerY = prefs.getFloat(KEY_CENTER_Y + index, defaults.centerY),
            fx = prefs.getFloat(KEY_FX + index, defaults.fx),
            fy = prefs.getFloat(KEY_FY + index, defaults.fy),
            sourceWidth = prefs.getFloat(KEY_SOURCE_WIDTH + index, defaults.sourceWidth),
            sourceHeight = prefs.getFloat(KEY_SOURCE_HEIGHT + index, defaults.sourceHeight),
        )
    }

    fun defaultParamsForIndex(index: Int): V2FisheyeParams = V2FisheyeParams.defaultForIndex(index)

    fun defaultBlindSpotParamsForIndex(index: Int): V2FisheyeParams =
        AVM_960_PARAMS.getOrElse(index) { defaultParamsForIndex(index) }

    fun blindSpotParamsForIndex(context: Context, index: Int): V2FisheyeParams {
        val defaults = defaultBlindSpotParamsForIndex(index)
        val prefs = prefs(context)
        return defaults.copy(
            k1 = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_K1 + index, defaults.k1),
            k2 = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_K2 + index, defaults.k2),
            k3 = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_K3 + index, defaults.k3),
            k4 = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_K4 + index, defaults.k4),
            zoom = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_ZOOM + index, defaults.zoom),
            centerX = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_CENTER_X + index, defaults.centerX),
            centerY = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_CENTER_Y + index, defaults.centerY),
            fx = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_FX + index, defaults.fx),
            fy = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_FY + index, defaults.fy),
            sourceWidth = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_SOURCE_WIDTH + index, defaults.sourceWidth),
            sourceHeight = prefs.getFloat(BLIND_SPOT_PREFIX + KEY_SOURCE_HEIGHT + index, defaults.sourceHeight),
        )
    }

    fun setParams(
        context: Context,
        index: Int,
        k1: Float,
        k2: Float,
        k3: Float = DEFAULT_K3,
        k4: Float = DEFAULT_K4,
        zoom: Float,
        centerX: Float = DEFAULT_CENTER_X,
        centerY: Float = DEFAULT_CENTER_Y,
        fx: Float = DEFAULT_FX,
        fy: Float = DEFAULT_FY,
        sourceWidth: Float = DEFAULT_SOURCE_WIDTH,
        sourceHeight: Float = DEFAULT_SOURCE_HEIGHT,
    ) {
        val label = defaultParamsForIndex(index).label
        prefs(context).edit()
            .putFloat(KEY_K1 + index, k1)
            .putFloat(KEY_K2 + index, k2)
            .putFloat(KEY_K3 + index, k3)
            .putFloat(KEY_K4 + index, k4)
            .putFloat(KEY_ZOOM + index, zoom.coerceAtLeast(0.1f))
            .putFloat(KEY_CENTER_X + index, centerX)
            .putFloat(KEY_CENTER_Y + index, centerY)
            .putFloat(KEY_FX + index, fx.coerceAtLeast(1.0f))
            .putFloat(KEY_FY + index, fy.coerceAtLeast(1.0f))
            .putFloat(KEY_SOURCE_WIDTH + index, sourceWidth.coerceAtLeast(1.0f))
            .putFloat(KEY_SOURCE_HEIGHT + index, sourceHeight.coerceAtLeast(1.0f))
            .apply()
        V2AppLog.i("V2FisheyeSettings", "params index=$index label=$label k1=$k1 k2=$k2 k3=$k3 k4=$k4 zoom=$zoom center=$centerX,$centerY fx=$fx fy=$fy size=${sourceWidth}x$sourceHeight")
    }

    fun setBlindSpotParams(
        context: Context,
        index: Int,
        k1: Float,
        k2: Float,
        k3: Float = DEFAULT_K3,
        k4: Float = DEFAULT_K4,
        zoom: Float,
        centerX: Float = DEFAULT_CENTER_X,
        centerY: Float = DEFAULT_CENTER_Y,
        fx: Float = DEFAULT_FX,
        fy: Float = DEFAULT_FY,
        sourceWidth: Float = DEFAULT_SOURCE_WIDTH,
        sourceHeight: Float = DEFAULT_SOURCE_HEIGHT,
    ) {
        val label = defaultBlindSpotParamsForIndex(index).label
        prefs(context).edit()
            .putFloat(BLIND_SPOT_PREFIX + KEY_K1 + index, k1)
            .putFloat(BLIND_SPOT_PREFIX + KEY_K2 + index, k2)
            .putFloat(BLIND_SPOT_PREFIX + KEY_K3 + index, k3)
            .putFloat(BLIND_SPOT_PREFIX + KEY_K4 + index, k4)
            .putFloat(BLIND_SPOT_PREFIX + KEY_ZOOM + index, zoom.coerceAtLeast(0.1f))
            .putFloat(BLIND_SPOT_PREFIX + KEY_CENTER_X + index, centerX)
            .putFloat(BLIND_SPOT_PREFIX + KEY_CENTER_Y + index, centerY)
            .putFloat(BLIND_SPOT_PREFIX + KEY_FX + index, fx.coerceAtLeast(1.0f))
            .putFloat(BLIND_SPOT_PREFIX + KEY_FY + index, fy.coerceAtLeast(1.0f))
            .putFloat(BLIND_SPOT_PREFIX + KEY_SOURCE_WIDTH + index, sourceWidth.coerceAtLeast(1.0f))
            .putFloat(BLIND_SPOT_PREFIX + KEY_SOURCE_HEIGHT + index, sourceHeight.coerceAtLeast(1.0f))
            .apply()
        V2AppLog.i("V2FisheyeSettings", "blindSpot params index=$index label=$label k1=$k1 k2=$k2 k3=$k3 k4=$k4 zoom=$zoom center=$centerX,$centerY fx=$fx fy=$fy size=${sourceWidth}x$sourceHeight")
    }

    fun applyAvm960Params(context: Context) {
        AVM_960_PARAMS.forEachIndexed { index, params ->
            setParams(
                context = context,
                index = index,
                k1 = params.k1,
                k2 = params.k2,
                k3 = params.k3,
                k4 = params.k4,
                zoom = params.zoom,
                centerX = params.centerX,
                centerY = params.centerY,
                fx = params.fx,
                fy = params.fy,
                sourceWidth = params.sourceWidth,
                sourceHeight = params.sourceHeight,
            )
        }
        V2AppLog.i("V2FisheyeSettings", "applied AVM 960 OpenCV params")
    }

    fun applyBlindSpotAvm960Params(context: Context) {
        AVM_960_PARAMS.forEachIndexed { index, params ->
            setBlindSpotParams(
                context = context,
                index = index,
                k1 = params.k1,
                k2 = params.k2,
                k3 = params.k3,
                k4 = params.k4,
                zoom = params.zoom,
                centerX = params.centerX,
                centerY = params.centerY,
                fx = params.fx,
                fy = params.fy,
                sourceWidth = params.sourceWidth,
                sourceHeight = params.sourceHeight,
            )
        }
        V2AppLog.i("V2FisheyeSettings", "applied blind spot AVM 960 OpenCV params")
    }

    fun resetAllParams(context: Context) {
        val editor = prefs(context).edit()
        repeat(V2_CAMERA_SLOT_COUNT) { index ->
            editor
                .remove(KEY_K1 + index)
                .remove(KEY_K2 + index)
                .remove(KEY_K3 + index)
                .remove(KEY_K4 + index)
                .remove(KEY_ZOOM + index)
                .remove(KEY_CENTER_X + index)
                .remove(KEY_CENTER_Y + index)
                .remove(KEY_FX + index)
                .remove(KEY_FY + index)
                .remove(KEY_SOURCE_WIDTH + index)
                .remove(KEY_SOURCE_HEIGHT + index)
        }
        editor.apply()
        V2AppLog.i("V2FisheyeSettings", "reset all params defaults=${paramsSummary()}")
    }

    fun resetBlindSpotParams(context: Context) {
        val editor = prefs(context).edit()
        repeat(V2_CAMERA_SLOT_COUNT) { index ->
            editor
                .remove(BLIND_SPOT_PREFIX + KEY_K1 + index)
                .remove(BLIND_SPOT_PREFIX + KEY_K2 + index)
                .remove(BLIND_SPOT_PREFIX + KEY_K3 + index)
                .remove(BLIND_SPOT_PREFIX + KEY_K4 + index)
                .remove(BLIND_SPOT_PREFIX + KEY_ZOOM + index)
                .remove(BLIND_SPOT_PREFIX + KEY_CENTER_X + index)
                .remove(BLIND_SPOT_PREFIX + KEY_CENTER_Y + index)
                .remove(BLIND_SPOT_PREFIX + KEY_FX + index)
                .remove(BLIND_SPOT_PREFIX + KEY_FY + index)
                .remove(BLIND_SPOT_PREFIX + KEY_SOURCE_WIDTH + index)
                .remove(BLIND_SPOT_PREFIX + KEY_SOURCE_HEIGHT + index)
        }
        editor.apply()
        V2AppLog.i("V2FisheyeSettings", "reset blind spot params defaults=${paramsSummary(context)}")
    }

    fun paramsSummary(context: Context? = null): String = if (context == null) {
        V2FisheyeParams.defaultSummary()
    } else {
        V2SettingsFormatter.fisheyeParamsSummary(context)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
