package com.kooo.evcam.v2.service

const val V2_CAMERA_SLOT_COUNT = 4

data class V2CameraSpec(
    val name: String,
    val label: String,
    val cameraId: String,
    val rotation: Int,
)

data class V2CameraSpecSet(
    val modelLabel: String,
    val specs: List<V2CameraSpec>,
)
