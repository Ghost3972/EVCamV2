package com.kooo.evcam.v2.ui.settings

import com.kooo.evcam.v2.service.V2_CAMERA_SLOT_COUNT
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2FisheyeParams
import com.kooo.evcam.v2.settings.V2FisheyeSettings
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal class V2FisheyeSettingsController(
    private val activity: V2SettingsActivity,
) {
    val previewIndices: IntRange = 0 until V2_CAMERA_SLOT_COUNT
    val blindSpotIndices: IntArray = intArrayOf(LEFT_INDEX, RIGHT_INDEX)

    fun isPreviewEnabled(): Boolean = V2FisheyeSettings.isEnabled(activity)

    fun isBlindSpotEnabled(): Boolean = V2FisheyeSettings.isBlindSpotEnabled(activity)

    fun setPreviewEnabled(enabled: Boolean) {
        V2FisheyeSettings.setEnabled(activity, enabled)
        refreshFisheye()
    }

    fun setBlindSpotEnabled(enabled: Boolean) {
        V2FisheyeSettings.setBlindSpotEnabled(activity, enabled)
        refreshFisheye()
    }

    fun previewParams(index: Int): V2FisheyeParams = V2FisheyeSettings.paramsForIndex(activity, index)

    fun blindSpotParams(index: Int): V2FisheyeParams = V2FisheyeSettings.blindSpotParamsForIndex(activity, index)

    fun savePreviewParams(index: Int, params: V2FisheyeParams) {
        V2FisheyeSettings.setParams(
            context = activity,
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
        refreshFisheye()
    }

    fun saveBlindSpotParams(index: Int, params: V2FisheyeParams) {
        V2FisheyeSettings.setBlindSpotParams(
            context = activity,
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
        refreshFisheye()
    }

    fun resetPreviewParams() {
        V2FisheyeSettings.resetAllParams(activity)
        refreshFisheye()
    }

    fun importPreviewAvm960Params() {
        V2FisheyeSettings.applyAvm960Params(activity)
        refreshFisheye()
    }

    fun resetBlindSpotParams() {
        V2FisheyeSettings.resetBlindSpotParams(activity)
        refreshFisheye()
    }

    fun importBlindSpotAvm960Params() {
        V2FisheyeSettings.applyBlindSpotAvm960Params(activity)
        refreshFisheye()
    }

    fun showPreview(index: Int) {
        V2CameraServiceCommands.showFisheyePreview(activity, index)
    }

    fun showBlindSpotPreview(index: Int) {
        V2CameraServiceCommands.showBlindSpotPreview(activity, blindSpotSideForIndex(index))
    }

    fun previewSubtitle(): String {
        val fisheye = V2SettingsRepository.currentSnapshot(activity).fisheye
        return "预览/录制鱼眼矫正用于主预览和录像\n" +
            V2FisheyeParams.summary(fisheye.params)
    }

    fun blindSpotSubtitle(): String {
        val fisheye = V2SettingsRepository.currentSnapshot(activity).fisheye
        val enabled = if (fisheye.blindSpotEnabled) "开" else "关"
        return "开启后补盲画面先做鱼眼矫正，再做补盲画面矫正；关闭时补盲画面不做鱼眼矫正\n" +
            "$enabled；${V2FisheyeParams.summary(fisheye.blindSpotParams)}"
    }

    private fun refreshFisheye() {
        V2CameraServiceCommands.refreshFisheye(activity)
    }

    private fun blindSpotSideForIndex(index: Int): String = if (index == RIGHT_INDEX) "right" else "left"

    private companion object {
        private const val LEFT_INDEX = 2
        private const val RIGHT_INDEX = 3
    }
}
