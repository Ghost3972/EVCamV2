package com.kooo.evcam.v2.service

import android.content.Context
import android.hardware.camera2.CameraManager
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

object V2CameraSpecProvider {
    fun current(context: Context): V2CameraSpecSet {
        val model = V2SettingsRepository.vehicleConfig(context)
        return V2CameraSpecSet(
            modelLabel = model.label,
            specs = specsForModel(context, model),
        )
    }

    fun specsForCurrentModel(context: Context): List<V2CameraSpec> {
        val model = V2SettingsRepository.vehicleConfig(context)
        return specsForModel(context, model)
    }

    private fun specsForModel(context: Context, model: V2SettingsSnapshot.Vehicle): List<V2CameraSpec> {
        val mapping = if (model.useMainBranchFourCameraFallback) {
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
        mapping: V2SettingsSnapshot.CameraMapping
    ): V2SettingsSnapshot.CameraMapping {
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
