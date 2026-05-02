package com.kooo.evcam.v2.settings

data class V2BlindSpotCorrection(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val rotation: Float = 0f,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
)
