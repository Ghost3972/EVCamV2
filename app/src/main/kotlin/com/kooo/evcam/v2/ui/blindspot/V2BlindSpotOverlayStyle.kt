package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

internal class V2BlindSpotOverlayStyle(private val context: Context) {
    fun controlButton(
        iconRes: Int,
        size: Int,
        iconSize: Int,
        backgroundAlpha: Float,
        onClick: (() -> Unit)? = null,
        onTouchListener: View.OnTouchListener? = null,
    ): FrameLayout {
        return FrameLayout(context).apply {
            background = controlBackground(backgroundAlpha)
            isClickable = true
            isFocusable = false
            if (onClick != null) setOnClickListener { onClick() }
            if (onTouchListener != null) setOnTouchListener(onTouchListener)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER
            }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }.also {
            it.layoutParams = FrameLayout.LayoutParams(size, size)
        }
    }

    fun roundedBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(34f).toFloat()
        setColor(0xF014171C.toInt())
        setStroke(dp(1f), 0x22FFFFFF)
    }

    fun controlBackground(alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val a = (0xFF * alpha.coerceIn(0f, 1f)).toInt()
        setColor((a shl 24) or 0x101216)
        setStroke(dp(1f), 0x1CFFFFFF)
    }

    fun pillBackground(alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15f).toFloat()
        val a = (0xFF * alpha.coerceIn(0f, 1f)).toInt()
        setColor((a shl 24) or 0x101216)
        setStroke(dp(1f), 0x1CFFFFFF)
    }

    fun dragBarBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(2f).toFloat()
        setColor(0xCCFFFFFF.toInt())
    }

    fun dp(value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    fun controlButtonSize(): Int = dp(52f)

    fun closeButtonSize(): Int = dp(46f)

    fun edgeControlMargin(): Int = dp(44f)

    fun topControlMargin(): Int = dp(22f)

    fun bottomControlMargin(): Int = dp(22f)
}
