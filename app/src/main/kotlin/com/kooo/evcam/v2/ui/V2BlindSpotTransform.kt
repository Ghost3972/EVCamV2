package com.kooo.evcam.v2.ui

import android.graphics.Matrix
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import kotlin.math.max

object V2BlindSpotTransform {
    fun apply(
        texture: TextureView?,
        overlayRotationDegrees: Int,
        correction: V2BlindSpotSettings.Correction,
        windowSwapped: Boolean,
        previewSize: Size?,
    ) {
        texture ?: return
        ensureMatchParentBounds(texture)
        texture.rotation = 0f
        texture.pivotX = texture.width / 2f
        texture.pivotY = texture.height / 2f
        texture.scaleX = 1f
        texture.scaleY = 1f
        texture.translationX = 0f
        texture.translationY = 0f
        texture.setTransform(matrix(texture, overlayRotationDegrees, correction, windowSwapped, previewSize))
    }

    fun effectiveRotation(overlayRotationDegrees: Int, correction: V2BlindSpotSettings.Correction): Float =
        normalizeRotation(overlayRotationDegrees + correction.rotation)

    fun isCloserToPortrait(rotation: Float): Boolean {
        val mod180 = normalizeRotation(rotation) % 180f
        return mod180 >= 45f && mod180 < 135f
    }

    private fun ensureMatchParentBounds(texture: TextureView) {
        val current = texture.layoutParams as? FrameLayout.LayoutParams
        if (current?.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            current.height == ViewGroup.LayoutParams.MATCH_PARENT &&
            current.gravity == Gravity.CENTER
        ) return
        texture.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
    }

    private fun matrix(
        texture: TextureView,
        overlayRotationDegrees: Int,
        correction: V2BlindSpotSettings.Correction,
        windowSwapped: Boolean,
        previewSize: Size?,
    ): Matrix {
        val width = texture.width.coerceAtLeast(1).toFloat()
        val height = texture.height.coerceAtLeast(1).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val baseRotation = 0
        val correctionRotation = effectiveRotation(overlayRotationDegrees, correction)
        val cropRotation = if (windowSwapped) normalizeRotation(baseRotation + correctionRotation) else normalizeRotation(baseRotation.toFloat())
        val morePortrait = isCloserToPortrait(cropRotation)
        val previewW = previewSize?.width ?: 0
        val previewH = previewSize?.height ?: 0
        val scaleX = correction.scaleX.coerceIn(MIN_CORRECTION_SCALE, MAX_CORRECTION_SCALE)
        val scaleY = correction.scaleY.coerceIn(MIN_CORRECTION_SCALE, MAX_CORRECTION_SCALE)
        val translateX = correction.translateX.coerceIn(MIN_CORRECTION_TRANSLATE, MAX_CORRECTION_TRANSLATE)
        val translateY = correction.translateY.coerceIn(MIN_CORRECTION_TRANSLATE, MAX_CORRECTION_TRANSLATE)
        val mirrorX = if (correction.mirrorH) -1f else 1f
        val mirrorY = if (correction.mirrorV) -1f else 1f
        return Matrix().apply {
            if (previewW > 0 && previewH > 0) {
                val effectivePreviewW = if (morePortrait) previewH.toFloat() else previewW.toFloat()
                val effectivePreviewH = if (morePortrait) previewW.toFloat() else previewH.toFloat()
                val previewAspect = effectivePreviewW / effectivePreviewH
                val viewAspect = width / height
                val scaleXFill: Float
                val scaleYFill: Float
                if (previewAspect > viewAspect) {
                    scaleXFill = previewAspect / viewAspect
                    scaleYFill = 1f
                } else {
                    scaleXFill = 1f
                    scaleYFill = viewAspect / previewAspect
                }
                postScale(scaleXFill, scaleYFill, centerX, centerY)
            }

            if (baseRotation != 0) {
                postRotate(baseRotation.toFloat(), centerX, centerY)
                if (baseRotation == 90 || baseRotation == 270) {
                    val scale = width / height
                    postScale(1f / scale, scale, centerX, centerY)
                }
            }

            if (correctionRotation != 0f && windowSwapped && previewW > 0 && previewH > 0) {
                reset()
                postScale(previewW.toFloat() / width, previewH.toFloat() / height, centerX, centerY)
                if (baseRotation != 0) postRotate(baseRotation.toFloat(), centerX, centerY)
                postRotate(correctionRotation, centerX, centerY)
                val fillScale = max(width / previewH.toFloat(), height / previewW.toFloat())
                postScale(fillScale, fillScale, centerX, centerY)
                postScale(scaleX, scaleY, centerX, centerY)
                postTranslate(translateX * width, translateY * height)
            } else {
                postScale(scaleX, scaleY, centerX, centerY)
                if (correctionRotation != 0f) postRotate(correctionRotation, centerX, centerY)
                postTranslate(translateX * width, translateY * height)
            }

            if (correction.mirrorH || correction.mirrorV) postScale(mirrorX, mirrorY, centerX, centerY)
        }
    }

    private fun normalizeRotation(rotation: Float): Float = ((rotation % 360f) + 360f) % 360f

    private const val MIN_CORRECTION_SCALE = 0.1f
    private const val MAX_CORRECTION_SCALE = 3.0f
    private const val MIN_CORRECTION_TRANSLATE = -1.0f
    private const val MAX_CORRECTION_TRANSLATE = 1.0f
}
