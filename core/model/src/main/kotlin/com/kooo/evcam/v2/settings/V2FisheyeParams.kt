package com.kooo.evcam.v2.settings

data class V2FisheyeParams(
    val label: String,
    val k1: Float,
    val k2: Float,
    val k3: Float = DEFAULT_K3,
    val k4: Float = DEFAULT_K4,
    val zoom: Float,
    val centerX: Float = DEFAULT_CENTER_X,
    val centerY: Float = DEFAULT_CENTER_Y,
    val fx: Float = DEFAULT_FX,
    val fy: Float = DEFAULT_FY,
    val sourceWidth: Float = DEFAULT_SOURCE_WIDTH,
    val sourceHeight: Float = DEFAULT_SOURCE_HEIGHT,
) {
    companion object {
        const val DEFAULT_K1 = 0.82f
        const val DEFAULT_K2 = 0.22f
        const val DEFAULT_K3 = 0.0f
        const val DEFAULT_K4 = 0.0f
        const val DEFAULT_ZOOM = 1.42f
        const val DEFAULT_CENTER_X = 0.5f
        const val DEFAULT_CENTER_Y = 0.5f
        const val DEFAULT_SOURCE_WIDTH = 1920.0f
        const val DEFAULT_SOURCE_HEIGHT = 1536.0f
        const val DEFAULT_FX = DEFAULT_SOURCE_WIDTH
        const val DEFAULT_FY = DEFAULT_SOURCE_HEIGHT

        private val DEFAULT_PARAMS = listOf(
            V2FisheyeParams(label = "前", k1 = 0.82f, k2 = 0.22f, zoom = 1.42f),
            V2FisheyeParams(label = "后", k1 = 0.78f, k2 = 0.20f, zoom = 1.38f),
            V2FisheyeParams(label = "左", k1 = 0.64f, k2 = 0.16f, zoom = 1.30f),
            V2FisheyeParams(label = "右", k1 = 0.66f, k2 = 0.17f, zoom = 1.32f),
        )

        fun defaultForIndex(index: Int): V2FisheyeParams = DEFAULT_PARAMS.getOrElse(index) { DEFAULT_PARAMS.first() }

        fun summary(params: List<V2FisheyeParams>): String = params.joinToString("；") { param ->
            "${param.label}:k1=${param.k1},k2=${param.k2},k3=${param.k3},k4=${param.k4},zoom=${param.zoom},center=${param.centerX}/${param.centerY},fx=${param.fx},fy=${param.fy},size=${param.sourceWidth}x${param.sourceHeight}"
        }

        fun defaultSummary(): String = summary(DEFAULT_PARAMS)
    }
}
