package com.kooo.evcam.v2.plugin

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.TextView
import com.flyme.auto.plugin.systemui.StatusBarPlugin
import com.flyme.plugin.annotations.Requires
import com.kooo.evcam.R
import com.kooo.evcam.v2.service.commands.V2CameraServiceContract
import com.kooo.evcam.v2.ui.main.V2MainActivity

@Requires(target = StatusBarPlugin::class, version = StatusBarPlugin.VERSION)
class V2StatusBarPlugin : Service(), StatusBarPlugin, View.OnClickListener {
    private var sysuiContext: Context? = null
    private var pluginContext: Context? = null
    private var callback: StatusBarPlugin.DialogCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val contextResolver = V2StatusBarContextResolver(APP_PACKAGE, TAG)
    private val userActions = V2StatusBarUserActionDispatcher(TAG)
    private var statusText: TextView? = null
    private var recordingLabel: TextView? = null
    private var recordingSwitch: CheckedTextView? = null
    private var notificationSeen = false
    private var notificationRecording = false
    private var lastNotificationStatus = ""

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
        this.pluginContext = pluginContext
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super<Service>.onDestroy()
    }

    override fun setDialogCallback(callback: StatusBarPlugin.DialogCallback?) {
        this.callback = callback
    }

    override fun getID(): Int = PLUGIN_ID

    override fun getVersion(): Int = StatusBarPlugin.VERSION

    override fun getDialogTitle(): String? = null

    override fun getDialogWidth(): Int =
        safeContext().resources.getDimensionPixelSize(R.dimen.v2_status_bar_plugin_width)

    override fun getDialogHeight(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun getDialogView(): View {
        val context = safeContext()
        val view = LayoutInflater.from(context).inflate(R.layout.layout_v2_status_bar_plugin, null, false)
        view.setOnClickListener(this)
        statusText = view.findViewById(R.id.tv_status_bar_recording_state)
        recordingLabel = view.findViewById(R.id.tv_status_bar_recording_label)
        recordingSwitch = view.findViewById(R.id.switch_status_bar_recording)
        view.findViewById<View?>(R.id.header_status_bar_recording)?.setOnClickListener(this)
        recordingSwitch?.setOnClickListener(this)
        view.findViewById<View?>(R.id.btn_status_bar_open)?.setOnClickListener(this)
        refreshState()
        return view
    }

    override fun onStatusIconPosted(sbn: StatusBarNotification) {
        val status = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (status == lastNotificationStatus) return
        lastNotificationStatus = status
        notificationSeen = true
        notificationRecording = status.contains("rec=ON")
        mainHandler.post(::refreshState)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.header_status_bar_recording, R.id.switch_status_bar_recording -> {
                Log.i(TAG, "plugin menu click: toggle recording")
                startServiceAction(V2CameraServiceContract.ACTION_TOGGLE_RECORDING_FROM_PLUGIN)
                scheduleRefresh()
            }
            R.id.btn_status_bar_open -> {
                Log.i(TAG, "plugin menu click: open main")
                openMainActivity()
            }
        }
    }

    private fun startServiceAction(action: String) {
        val intent = Intent()
            .setComponent(ComponentName(APP_PACKAGE, "com.kooo.evcam.v2.plugin.V2StatusBarPluginReceiver"))
            .setAction(action)
        try {
            userActions.sendBroadcast(hostContext(), intent)
        } catch (error: RuntimeException) {
            Log.e(TAG, "send plugin command failed", error)
        }
    }

    private fun openMainActivity() {
        val intent = Intent()
            .setComponent(ComponentName(APP_PACKAGE, "com.kooo.evcam.v2.ui.main.V2MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            userActions.startActivity(hostContext(), intent)
            callback?.dismissDialog()
        } catch (error: RuntimeException) {
            Log.e(TAG, "open main activity failed", error)
        }
    }

    private fun scheduleRefresh() {
        mainHandler.postDelayed(::refreshState, REFRESH_DELAY_MS)
    }

    private fun refreshState() {
        val switch = recordingSwitch ?: return
        val text = statusText ?: return
        val snapshot = V2StatusBarStateStore.read(stateContext())
        val serviceReady = notificationSeen || snapshot.serviceReady
        val recording = if (notificationSeen) notificationRecording else snapshot.recording
        renderState(serviceReady, recording)
    }

    private fun renderState(serviceReady: Boolean, recording: Boolean) {
        val switch = recordingSwitch ?: return
        val text = statusText ?: return
        switch.isChecked = recording
        recordingLabel?.text = "行车记录仪"
        text.text = statusLabel(serviceReady, recording)
    }

    private fun statusLabel(serviceReady: Boolean, recording: Boolean): String = when {
        !serviceReady -> "未录制"
        recording -> "循环录制中"
        else -> "未录制"
    }

    private fun safeContext(): Context = stateContext() ?: hostContext()

    private fun stateContext(): Context? = contextResolver.stateContext(pluginContext, hostContextOrNull())

    private fun hostContext(): Context = hostContextOrNull() ?: this

    private fun hostContextOrNull(): Context? = contextResolver.hostContextOrNull(sysuiContext)

    companion object {
        private const val TAG = "V2StatusBarPlugin"
        private const val APP_PACKAGE = "com.kooo.evcam.v2"
        private const val PLUGIN_ID = 132
        private const val REFRESH_DELAY_MS = 1_000L
    }
}
