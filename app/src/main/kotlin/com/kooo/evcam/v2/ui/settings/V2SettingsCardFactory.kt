package com.kooo.evcam.v2.ui.settings

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R

class V2SettingsCardFactory(private val activity: V2SettingsActivity) {
    fun cardContainer(bottomMarginDp: Int = 16): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_control_button_bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
    }

    fun cardTexts(
        title: String,
        subtitle: String,
        subtitleEndPaddingDp: Int = 12,
        useWeight: Boolean = true
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = if (useWeight) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(TextView(activity).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = 14f
            setPadding(0, dp(4), dp(subtitleEndPaddingDp), 0)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        })
    }

    fun cardRow(bottomMarginDp: Int = 16, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_control_button_bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun switchRow(enabled: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        isClickable = enabled
        isFocusable = enabled
        if (onClick != null) setOnClickListener { if (enabled) onClick() }
    }

    fun entryCard(title: String, subtitle: String, buttonText: String?, onClick: (() -> Unit)?): View {
        val row = cardRow(onClick = onClick)
        row.addView(cardTexts(title, subtitle, 0))
        if (buttonText != null && onClick != null) {
            row.addView(Button(activity).apply {
                text = buttonText
                textSize = 16f
                minHeight = dp(48)
                setTextColor(ContextCompat.getColor(activity, R.color.button_text))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.button_background))
                setOnClickListener { onClick() }
            })
        }
        return row
    }

    fun switchCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val row = cardRow().apply {
            alpha = if (enabled) 1f else 0.5f
            isClickable = enabled
            isFocusable = enabled
        }
        val texts = cardTexts(title, subtitle)
        val switch = Switch(activity).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        }
        row.setOnClickListener { if (enabled) switch.toggle() }
        row.addView(texts)
        row.addView(switch)
        return row
    }

    fun header(title: String, buttonText: String, onButtonClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(18), dp(18), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(98))

        addView(TextView(activity).apply {
            text = title
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(TextView(activity).apply {
            text = buttonText
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.button_text))
            background = ContextCompat.getDrawable(activity, R.drawable.v2_control_button_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onButtonClick() }
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
    }

    fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
