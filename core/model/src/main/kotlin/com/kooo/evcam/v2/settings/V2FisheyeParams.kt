package com.kooo.evcam.v2.settings

data class V2FisheyeParams(
    val label: String,
    val k1: Float,
    val k2: Float,
    val zoom: Float,
    val centerX: Float = DEFAULT_CENTER_X,
    val centerY: Float = DEFAULT_CENTER_Y,
) {
    companion object {
        const val DEFAULT_K1 = 0.82f
        const val DEFAULT_K2 = 0.22f
        const val DEFAULT_ZOOM = 1.42f
        const val DEFAULT_CENTER_X = 0.5f
        const val DEFAULT_CENTER_Y = 0.5f

        private val DEFAULT_PARAMS = listOf(
            V2FisheyeParams(label = "前", k1 = 0.82f, k2 = 0.22f, zoom = 1.42f),
            V2FisheyeParams(label = "后", k1 = 0.78f, k2 = 0.20f, zoom = 1.38f),
            V2FisheyeParams(label = "左", k1 = 0.64f, k2 = 0.16f, zoom = 1.30f),
            V2FisheyeParams(label = "右", k1 = 0.66f, k2 = 0.17f, zoom = 1.32f),
        )

        fun defaultForIndex(index: Int): V2FisheyeParams = DEFAULT_PARAMS.getOrElse(index) { DEFAULT_PARAMS.first() }

        fun summary(params: List<V2FisheyeParams>): String = params.joinToString("；") { param ->
            "${param.label}:k1=${param.k1},k2=${param.k2},zoom=${param.zoom}"
        }

        fun defaultSummary(): String = summary(DEFAULT_PARAMS)
    }
}
