package com.kooo.evcam.v2.settings

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog

object V2CameraCapabilityResolver {
    fun commonSupportedSurfaceTextureSizes(context: Context): List<Size> {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = V2VehicleModelSettings.getModel(app).mapping.run { listOf(front, back, left, right) }.distinct()
        val availableIds = runCatching { manager.cameraIdList.toSet() }.getOrElse {
            V2AppLog.e(TAG, "read cameraIdList failed", it)
            emptySet()
        }
        val perCamera = ids.filter { it in availableIds }.mapNotNull { id -> supportedSizesForCamera(manager, id) }
        val common = perCamera.reduceOrNull { acc, sizes -> acc.intersect(sizes).toSet() }.orEmpty()
        val source = if (common.isNotEmpty()) common else perCamera.flatten().toSet()
        return source
            .filter { it.width > 0 && it.height > 0 }
            .map { normalizeLandscape(it) }
            .distinctBy { valueForSize(it) }
            .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
    }

    private fun supportedSizesForCamera(manager: CameraManager, cameraId: String): Set<Size>? = try {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()
        map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { normalizeLandscape(it) }
            ?.toSet()
            .orEmpty()
    } catch (error: CameraAccessException) {
        V2AppLog.e(TAG, "read supported sizes failed camera=$cameraId", error)
        null
    } catch (error: RuntimeException) {
        V2AppLog.e(TAG, "read supported sizes failed camera=$cameraId", error)
        null
    }

    private fun normalizeLandscape(size: Size): Size = if (size.width >= size.height) size else Size(size.height, size.width)
    private fun valueForSize(size: Size) = "${size.width}x${size.height}"

    private const val TAG = "V2CameraCapabilityResolver"
}
