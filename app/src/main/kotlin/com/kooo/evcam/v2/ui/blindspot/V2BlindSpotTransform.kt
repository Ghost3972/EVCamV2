package com.kooo.evcam.v2.ui.blindspot

import android.graphics.Matrix
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.kooo.evcam.v2.settings.V2BlindSpotCorrection
import com.kooo.evcam.v2.settings.V2BlindSpotSettings

object V2BlindSpotTransform {
    fun apply(
        texture: TextureView?,
        overlayRotationDegrees: Int,
        correction: V2BlindSpotCorrection,
    ) {
        texture ?: return
        ensureMatchParentBounds(texture)
        texture.post {
            if (texture.width <= 0 || texture.height <= 0) return@post
            texture.rotation = 0f
            texture.pivotX = texture.width / 2f
            texture.pivotY = texture.height / 2f
            texture.scaleX = 1f
            texture.scaleY = 1f
            texture.translationX = 0f
            texture.translationY = 0f
            texture.setTransform(matrix(texture, overlayRotationDegrees, correction))
        }
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
        correction: V2BlindSpotCorrection,
    ): Matrix {
        val width = texture.width.coerceAtLeast(1).toFloat()
        val height = texture.height.coerceAtLeast(1).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val baseRotation = normalizeRotation(overlayRotationDegrees.toFloat())
        val correctionRotation = V2BlindSpotSettings.normalizeCorrectionRotation(correction.rotation)
        val scaleX = correction.scaleX.coerceIn(
            V2BlindSpotSettings.MIN_CORRECTION_SCALE,
            V2BlindSpotSettings.MAX_CORRECTION_SCALE,
        )
        val scaleY = correction.scaleY.coerceIn(
            V2BlindSpotSettings.MIN_CORRECTION_SCALE,
            V2BlindSpotSettings.MAX_CORRECTION_SCALE,
        )
        val translateX = correction.translateX.coerceIn(
            V2BlindSpotSettings.MIN_CORRECTION_TRANSLATE,
            V2BlindSpotSettings.MAX_CORRECTION_TRANSLATE,
        )
        val translateY = correction.translateY.coerceIn(
            V2BlindSpotSettings.MIN_CORRECTION_TRANSLATE,
            V2BlindSpotSettings.MAX_CORRECTION_TRANSLATE,
        )
        val mirrorX = if (correction.mirrorH) -1f else 1f
        val mirrorY = if (correction.mirrorV) -1f else 1f
        return Matrix().apply {
            if (baseRotation != 0f) {
                postRotate(baseRotation, centerX, centerY)
                if (baseRotation == 90f || baseRotation == 270f) {
                    val scale = width / height
                    postScale(scale, 1f / scale, centerX, centerY)
                }
            }

            postScale(scaleX, scaleY, centerX, centerY)
            if (correctionRotation != 0f) postRotate(correctionRotation, centerX, centerY)
            postTranslate(translateX * width, translateY * height)

            if (correction.mirrorH || correction.mirrorV) postScale(mirrorX, mirrorY, centerX, centerY)
        }
    }

    private fun normalizeRotation(rotation: Float): Float = ((rotation % 360f) + 360f) % 360f
}
