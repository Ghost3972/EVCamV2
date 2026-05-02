package com.kooo.evcam.v2.service

import android.os.Build
import android.provider.Settings
import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.ui.V2FisheyePreviewOverlay

internal class V2FisheyePreviewController(
    private val service: V2CameraForegroundService,
    private val engine: V2CameraEngine,
    private val previewSurfaces: Array<Surface?>,
    private val isDisplayPowerOn: () -> Boolean,
    private val showToast: (String) -> Unit,
) {
    private var overlay: V2FisheyePreviewOverlay? = null
    private var cameraIndex = -1

    fun show(index: Int) {
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "fisheye preview skipped: display off index=$index")
            showToast("屏幕关闭，无法打开鱼眼预览")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(service)) {
            V2AppLog.w(TAG, "fisheye preview skipped: overlay permission missing")
            showToast("鱼眼预览需要悬浮窗权限")
            return
        }
        if (!service.canShowFisheyePreview(index)) {
            showToast("补盲显示中，暂不打开鱼眼预览")
            return
        }

        val params = V2SettingsRepository.fisheyeConfig(service).paramsForIndex(index)
        val previousIndex = cameraIndex
        cameraIndex = index
        engine.applyFisheyeSettings()
        previewOverlay().show(params.label, index)
        restoreMainPreviewIfNeeded(previousIndex)
        V2AppLog.i(TAG, "fisheye preview show index=$index label=${params.label}")
    }

    fun hide() {
        val index = cameraIndex
        overlay?.hide()
        cameraIndex = -1
        restoreMainPreviewIfNeeded(index)
        V2AppLog.i(TAG, "fisheye preview hidden index=$index")
    }

    private fun previewOverlay(): V2FisheyePreviewOverlay {
        return overlay ?: V2FisheyePreviewOverlay(
            service,
            attachPreview = { index, surface -> service.attachFisheyePreviewSurface(index, surface) },
            detachPreview = { index -> service.detachFisheyePreviewSurface(index) },
            onClose = { hide() }
        ).also { overlay = it }
    }

    private fun restoreMainPreviewIfNeeded(index: Int) {
        if (index < 0 || !isDisplayPowerOn()) return
        previewSurfaces.getOrNull(index)?.takeIf { it.isValid }?.let { service.attachPreviewSurface(index, it) }
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
