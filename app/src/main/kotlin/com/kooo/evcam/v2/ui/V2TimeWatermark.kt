package com.kooo.evcam.v2.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object V2TimeWatermark {
    private const val PATTERN = "yyyy年MM月dd日 HH:mm:ss"
    private const val TEXT_WIDTH_DP = 500
    private const val TEXT_SIZE_SP = 38f
    private const val OVERLAY_X_DP = 50
    private const val OVERLAY_Y_DP = 40
    private const val NEXT_SECOND_MARGIN_MS = 20L

    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat(PATTERN, Locale.CHINA)
    }

    fun format(wallClockMs: Long = System.currentTimeMillis()): String =
        (formatter.get() ?: SimpleDateFormat(PATTERN, Locale.CHINA)).format(Date(wallClockMs))

    fun nextSecondDelayMs(nowMs: Long = System.currentTimeMillis()): Long =
        (1_000L - (nowMs % 1_000L) + NEXT_SECOND_MARGIN_MS).coerceIn(50L, 1_050L)

    fun applyStyle(view: TextView) {
        view.setSingleLine(true)
        view.includeFontPadding = true
        view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        view.setPadding(0, 0, 0, 0)
        view.setTextColor(ContextCompat.getColor(view.context, R.color.home_overlay_text))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        view.typeface = Typeface.DEFAULT
        view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.letterSpacing = 0f
    }

    fun renderBitmap(context: Context, wallClockMs: Long = System.currentTimeMillis()): Bitmap {
        val themed = ContextThemeWrapper(context, R.style.Theme_Cam)
        val width = dp(themed, TEXT_WIDTH_DP)
        val view = TextView(themed).apply {
            applyStyle(this)
            text = format(wallClockMs)
            visibility = View.VISIBLE
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val height = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            view.draw(Canvas(bitmap))
        }
    }

    fun overlayX(context: Context): Int = dp(context, OVERLAY_X_DP)

    fun overlayY(context: Context): Int = dp(context, OVERLAY_Y_DP)

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).roundToInt()
}
