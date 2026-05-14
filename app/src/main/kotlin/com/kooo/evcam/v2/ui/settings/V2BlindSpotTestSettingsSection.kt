package com.kooo.evcam.v2.ui.settings

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.ui.blindspot.V2BlindSpotTestOverlay

internal class V2BlindSpotTestSettingsSection(
    private val activity: V2SettingsActivity,
    private val cards: V2SettingsCardFactory,
) {
    private var testOverlay: V2BlindSpotTestOverlay? = null

    fun create(visible: Boolean): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        card.addView(cards.cardTexts(
            title = "测试悬浮窗",
            subtitle = "开启后显示左/右按钮悬浮窗，点击可模拟转向灯信号触发补盲",
            useWeight = false,
        ))
        card.addView(enableRow())
        return card
    }

    private fun enableRow(): View {
        val row = cards.switchRow()
        row.addView(TextView(activity).apply {
            text = "显示测试悬浮窗"
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = cards.settingSwitch(false) { enabled ->
            if (enabled) {
                showTestOverlay()
            } else {
                hideTestOverlay()
            }
        }
        row.addView(switch)
        return row
    }

    private fun showTestOverlay() {
        if (testOverlay?.isShowing() == true) return
        val overlay = V2BlindSpotTestOverlay(activity.applicationContext)
        overlay.show()
        testOverlay = overlay
    }

    private fun hideTestOverlay() {
        testOverlay?.hide()
        testOverlay = null
    }

    private fun dp(value: Int): Int = cards.dp(value)
}
