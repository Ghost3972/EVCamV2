package com.kooo.evcam.v2.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R

internal data class V2PermissionLogViews(val container: ScrollView, val text: TextView) {
    fun show() {
        container.visibility = View.VISIBLE
    }

    fun append(message: String) {
        text.append(message + "\n")
        container.post { container.fullScroll(View.FOCUS_DOWN) }
    }
}

internal class V2PermissionDialogViews(private val context: Context) {
    fun rootScroll(): ScrollView = ScrollView(context).apply {
        setBackgroundColor(color(R.color.page_background))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun contentColumn(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    fun permissionRow(
        title: String,
        subtitleProvider: () -> String,
        refreshers: MutableList<() -> Unit>,
        buttonText: String = "去授权",
        onClick: () -> Unit,
    ): View = cardRow(title, subtitleProvider(), buttonText, onClick).also { row ->
        val statusView = (((row as? LinearLayout)?.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? TextView)
        refreshers.add { statusView?.text = subtitleProvider() }
    }

    fun cardRow(title: String, subtitle: String, buttonText: String?, onClick: (() -> Unit)?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cardBackground()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(cardTitle(title))
        texts.addView(cardSubtitle(subtitle))
        row.addView(texts)
        if (buttonText != null && onClick != null) row.addView(button(buttonText, color(R.color.button_background)).apply { setOnClickListener { onClick() } })
        return row
    }

    fun verticalCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = cardBackground()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        }
    }

    fun button(textValue: String, colorValue: Int): Button = Button(context).apply {
        text = textValue
        textSize = 14f
        minHeight = dp(40)
        setTextColor(color(R.color.button_text))
        backgroundTintList = android.content.res.ColorStateList.valueOf(colorValue)
    }

    fun title(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(R.color.text_primary))
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(16))
    }

    fun description(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(R.color.text_secondary))
        textSize = 14f
        setPadding(0, 0, 0, dp(16))
    }

    fun sectionTitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(R.color.text_primary))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(12))
    }

    fun cardTitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(R.color.text_primary))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun cardSubtitle(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(R.color.text_secondary))
        textSize = 14f
        setPadding(0, dp(3), 0, dp(8))
    }

    fun logView(): V2PermissionLogViews {
        val text = TextView(context).apply {
            setTextColor(color(R.color.text_primary))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(context).apply {
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(color(R.color.page_background))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply {
                topMargin = dp(12)
            }
            addView(text)
        }
        return V2PermissionLogViews(scroll, text)
    }

    fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(color(R.color.card_background))
        cornerRadius = dp(12).toFloat()
    }

    fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
