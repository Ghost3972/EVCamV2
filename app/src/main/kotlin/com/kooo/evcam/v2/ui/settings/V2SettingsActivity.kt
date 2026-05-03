package com.kooo.evcam.v2.ui.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraServiceCommands

class V2SettingsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private val cards by lazy { V2SettingsCardFactory(this) }
    private val recordingSection by lazy { V2RecordingSettingsSection(this, cards) }
    private val storageSection by lazy { V2StorageSettingsSection(this, cards) }
    private val avoidanceSection by lazy { V2AvoidanceSettingsSection(this, cards) }
    private val fisheyeSection by lazy { V2FisheyeSettingsSection(this, cards) }
    private val signalSection by lazy { V2SignalSettingsSection(this, cards) }
    private val generalSection by lazy { V2GeneralSettingsSection(this, cards) }
    private var showingPermissionPage = false
    private var homeScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2SettingsActivity", "onCreate")
        root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        setContentView(root)
        showHomePage()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                V2AppLog.i("V2SettingsActivity", "back pressed permissionPage=$showingPermissionPage")
                if (showingPermissionPage) showHomePage() else finish()
            }
        })
    }

    override fun onDestroy() {
        V2AppLog.i("V2SettingsActivity", "onDestroy: hide fisheye preview overlay")
        V2CameraServiceCommands.hideFisheyePreview(this)
        V2CameraServiceCommands.hideBlindSpotPreview(this)
        super.onDestroy()
    }

    private fun showHomePage(restoreScrollY: Int? = null) {
        showingPermissionPage = false
        V2AppLog.i("V2SettingsActivity", "show home page")
        root.removeAllViews()
        root.addView(createPage("软件设置", "⌂", { finish() }, createHomeContent()), cards.fullScreenParams())
        if (restoreScrollY != null) {
            homeScrollView?.post { homeScrollView?.scrollTo(0, restoreScrollY) }
        }
    }

    private fun showPermissionPage() {
        showingPermissionPage = true
        V2AppLog.i("V2SettingsActivity", "show permission page")
        root.removeAllViews()
        root.addView(
            createPage(
                "权限设置",
                "←",
                { showHomePage() },
                V2PermissionSettingsDialog.createPageView(this, showTitle = false)
            ),
            cards.fullScreenParams()
        )
    }

    private fun createPage(title: String, buttonText: String, onButtonClick: () -> Unit, contentView: View): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        page.addView(cards.header(title, buttonText, onButtonClick))
        page.addView(contentView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return page
    }

    private fun createHomeContent(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        homeScrollView = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        scroll.addView(content)

        content.addView(generalSection.versionCard())
        content.addView(generalSection.keepAliveStatusCard())
        content.addView(generalSection.vehicleModelCard())
        content.addView(cards.entryCard(
            title = "权限设置",
            subtitle = "ADB 一键获取、系统白名单、电池优化、悬浮窗、无障碍等",
            buttonText = "进入 →",
            onClick = { showPermissionPage() }
        ))
        content.addView(generalSection.logExportCard())
        content.addView(generalSection.startupSwitchCard())
        content.addView(generalSection.recordingSwitchCard())
        content.addView(recordingSettingsCard())
        content.addView(storageCleanupCard())
        content.addView(signalSection.customKeyCard())
        content.addView(avoidanceBehaviorCard())
        content.addView(signalSection.blindSpotCard())
        content.addView(fisheyeSwitchCard())
        return scroll
    }

    private fun recordingSettingsCard(): View = recordingSection.create()

    private fun storageCleanupCard(): View = storageSection.create()

    private fun avoidanceBehaviorCard(): View = avoidanceSection.create()

    private fun fisheyeSwitchCard(): View = fisheyeSection.create()

    private fun dp(value: Int): Int = cards.dp(value)
    }
