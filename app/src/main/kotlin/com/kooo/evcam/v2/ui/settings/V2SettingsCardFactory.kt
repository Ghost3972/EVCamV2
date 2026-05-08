package com.kooo.evcam.v2.ui.settings

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.kooo.evcam.R

class V2SettingsCardFactory(private val activity: V2SettingsActivity) {
    fun cardContainer(bottomMarginDp: Int = 16): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(28), dp(32), dp(28))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_card_bg)
        minimumHeight = dp(128)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp.coerceAtLeast(12))
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
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = 22f
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(8), dp(subtitleEndPaddingDp), 0)
            setTextColor(ContextCompat.getColor(activity, R.color.settings_title_secondary))
        })
    }

    fun cardRow(bottomMarginDp: Int = 16, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(32), dp(24), dp(32), dp(24))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_card_bg)
        minimumHeight = dp(128)
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
        setPadding(0, dp(6), 0, dp(6))
        isClickable = enabled && onClick != null
        isFocusable = enabled && onClick != null
        if (onClick != null) setOnClickListener { if (enabled) onClick() }
    }

    fun entryCard(title: String, subtitle: String, buttonText: String?, onClick: (() -> Unit)?): View {
        val row = cardRow(onClick = onClick)
        row.addView(cardTexts(title, subtitle, 0))
        if (buttonText != null && onClick != null) {
            row.addView(actionButton(buttonText, onClick, minWidthDp = 176, minHeightDp = 88))
        }
        return row
    }

    fun actionButton(
        text: String,
        onClick: () -> Unit,
        minWidthDp: Int = 170,
        minHeightDp: Int = 92,
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = 19f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(minWidthDp)
        minHeight = dp(minHeightDp)
        setPadding(dp(24), dp(14), dp(24), dp(14))
        setTextColor(ContextCompat.getColor(activity, R.color.settings_button_text))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_action_bg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun backButton(onClick: () -> Unit): TextView = TextView(activity).apply {
        text = "←"
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(72)
        minHeight = dp(72)
        setTextColor(ContextCompat.getColor(activity, R.color.settings_button_text))
        background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_back_button_bg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
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
        }
        val texts = cardTexts(title, subtitle)
        val switch = settingSwitch(checked, enabled, onCheckedChange)
        row.addView(texts)
        row.addView(switch)
        return row
    }

    fun settingSwitch(
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ): CheckedTextView = CheckedTextView(activity).apply {
        isChecked = checked
        isEnabled = enabled
        minWidth = dp(78)
        minHeight = dp(40)
        background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_switch_selector)
        isClickable = true
        isFocusable = true
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener {
            if (!isEnabled) return@setOnClickListener
            isChecked = !isChecked
            onCheckedChange(isChecked)
        }
    }

    fun styleCheckBox(checkBox: CheckBox): CheckBox = checkBox.apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        minHeight = dp(48)
        minWidth = dp(48)
        buttonDrawable = ContextCompat.getDrawable(activity, R.drawable.v2_settings_checkbox_selector)
        setCompoundDrawablePadding(dp(10))
        setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
        setPadding(dp(2), dp(8), dp(18), dp(8))
    }

    fun styleSlider(seekBar: SeekBar): SeekBar = seekBar.apply {
        minHeight = dp(40)
        maxHeight = dp(40)
        progressDrawable = ContextCompat.getDrawable(activity, R.drawable.v2_settings_slider_progress)
        thumb = ContextCompat.getDrawable(activity, R.drawable.v2_settings_slider_thumb)
        splitTrack = false
        thumbOffset = dp(9)
        setPadding(0, 0, 0, 0)
    }

    fun header(title: String, onBackClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(24), 0, dp(24))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))

        addView(backButton(onBackClick))
        addView(TextView(activity).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(20), 0, 0, 0)
            setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun styleFieldText(view: TextView, dropdown: Boolean) = view.apply {
        textSize = 17f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
        setBackgroundResource(R.drawable.v2_settings_field_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (dropdown) elevation = 0f
    }

    fun styleSpinnerText(view: TextView, dropdown: Boolean) = view.apply {
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
        if (dropdown) {
            setBackgroundResource(R.drawable.v2_settings_spinner_dropdown_item_bg)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        } else {
            setBackgroundResource(R.drawable.v2_settings_spinner_bg)
            setPadding(dp(18), dp(16), dp(54), dp(16))
        }
    }

    fun dropdownField(
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
        canSelect: ((Int) -> Boolean)? = null,
        widthDp: Int? = null,
    ): View = DropdownFieldView(labels, selectedIndex, onSelected, canSelect).apply {
        layoutParams = if (widthDp == null) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private inner class DropdownFieldView(
        private val labels: List<String>,
        selectedIndex: Int,
        private val onSelected: (Int) -> Unit,
        private val canSelect: ((Int) -> Boolean)?
    ) : LinearLayout(activity) {
        private val labelView = TextView(activity)
        private val chevronView = ImageView(activity)
        private var currentIndex = selectedIndex.coerceIn(0, labels.lastIndex.coerceAtLeast(0))

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_field_bg)
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(12), dp(14), dp(12))

            labelView.apply {
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
            }
            addView(labelView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            chevronView.apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = ContextCompat.getColorStateList(activity, R.color.settings_spinner_chevron)
                alpha = 0.9f
                scaleType = ImageView.ScaleType.CENTER
            }
            addView(chevronView, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })

            updateSelection(currentIndex, notify = false)
            setOnClickListener { showPopup() }
        }

        private fun updateSelection(index: Int, notify: Boolean = true) {
            if (labels.isEmpty()) return
            currentIndex = index.coerceIn(0, labels.lastIndex)
            labelView.text = labels[currentIndex]
            if (notify) onSelected(currentIndex)
        }

        private fun showPopup() {
            if (labels.isEmpty()) return
            var popup: PopupWindow? = null
            val popupContent = buildPopupContent { popup?.dismiss() }
            val popupWidth = width.takeIf { it > 0 } ?: measuredWidth.takeIf { it > 0 } ?: dp(280)
            popup = PopupWindow(popupContent, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                isOutsideTouchable = true
                isClippingEnabled = false
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                elevation = 0f
                animationStyle = 0
            }
            popup.showAsDropDown(this, 0, dp(8))
        }

        private fun buildPopupContent(dismissPopup: () -> Unit): View {
            val root = FrameLayout(activity).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_dropdown_popup_bg)
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                overScrollMode = OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val container = LinearLayout(activity).apply { orientation = VERTICAL }

            labels.forEachIndexed { index, text ->
                val selected = index == currentIndex
                val row = LinearLayout(activity).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(60)
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    background = ContextCompat.getDrawable(
                        activity,
                        if (selected) R.drawable.v2_settings_dropdown_row_selected_bg else R.drawable.v2_settings_dropdown_row_bg
                    )
                }

                val textView = TextView(activity).apply {
                    this.text = text
                    textSize = 21f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(activity, R.color.settings_title_primary))
                }
                row.addView(textView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val selectedDot = View(activity).apply {
                    background = ContextCompat.getDrawable(activity, R.drawable.v2_settings_dropdown_selected_dot_bg)
                    alpha = if (selected) 1f else 0f
                }
                row.addView(selectedDot, LayoutParams(dp(8), dp(8)).apply { marginStart = dp(12) })

                row.setOnClickListener {
                    if (index == currentIndex) {
                        dismissPopup()
                        return@setOnClickListener
                    }
                    val accepted = canSelect?.invoke(index) ?: true
                    if (!accepted) return@setOnClickListener
                    updateSelection(index)
                    dismissPopup()
                }

                container.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (index != labels.lastIndex) {
                    container.addView(View(activity).apply {
                        setBackgroundColor(ContextCompat.getColor(activity, R.color.settings_card_stroke))
                        alpha = 0.35f
                    }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(18)
                        marginEnd = dp(18)
                    })
                }
            }

            scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return root
        }
    }

    fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
