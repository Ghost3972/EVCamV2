package com.kooo.evcam.v2.service

import android.content.Context
import com.kooo.evcam.v2.settings.V2VehicleModelSettings

data class V2CameraSpec(
    val name: String,
    val label: String,
    val cameraId: String,
    val rotation: Int
)

object V2CameraSpecProvider {
    fun specsForCurrentModel(context: Context): List<V2CameraSpec> {
        val mapping = V2VehicleModelSettings.getModel(context).mapping
        return listOf(
            V2CameraSpec("front", "前", mapping.front, 0),
            V2CameraSpec("back", "后", mapping.back, 0),
            V2CameraSpec("left", "左", mapping.left, 270),
            V2CameraSpec("right", "右", mapping.right, 90)
        )
    }
}
