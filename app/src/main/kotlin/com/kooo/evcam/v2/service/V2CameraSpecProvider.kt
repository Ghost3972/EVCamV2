package com.kooo.evcam.v2.service

import android.content.Context
import android.hardware.camera2.CameraManager
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2VehicleModelSettings

data class V2CameraSpec(
    val name: String,
    val label: String,
    val cameraId: String,
    val rotation: Int
)

object V2CameraSpecProvider {
    fun specsForCurrentModel(context: Context): List<V2CameraSpec> {
        val model = V2VehicleModelSettings.getModel(context)
        val mapping = if (model.id == V2VehicleModelSettings.MODEL_XINGHAN_7_2026) {
            xinghan7MappingWithMainFallback(context, model.mapping)
        } else {
            model.mapping
        }
        return listOf(
            V2CameraSpec("front", "前", mapping.front, 0),
            V2CameraSpec("back", "后", mapping.back, 0),
            V2CameraSpec("left", "左", mapping.left, 270),
            V2CameraSpec("right", "右", mapping.right, 90)
        )
    }

    private fun xinghan7MappingWithMainFallback(
        context: Context,
        mapping: V2VehicleModelSettings.CameraMapping
    ): V2VehicleModelSettings.CameraMapping {
        val cameraIds = runCatching {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList()
        }.onFailure {
            V2AppLog.e(TAG, "read cameraIdList failed for Xinghan7 fallback", it)
        }.getOrDefault(emptyList())

        if (cameraIds.size >= 5 || cameraIds.contains(mapping.left) || cameraIds.size < 4) return mapping

        val fallback = mapping.copy(left = cameraIds[0])
        V2AppLog.w(
            TAG,
            "Xinghan7 left camera ${mapping.left} unavailable, fallback to main-branch 4-camera layout left=${fallback.left} available=$cameraIds"
        )
        return fallback
    }

    private const val TAG = "V2CameraSpecProvider"
}
