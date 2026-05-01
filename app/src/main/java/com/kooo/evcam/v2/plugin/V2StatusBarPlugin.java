package com.kooo.evcam.v2.plugin;

import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.TextView;

import android.service.notification.StatusBarNotification;

import com.flyme.auto.plugin.systemui.StatusBarPlugin;
import com.flyme.plugin.annotations.Requires;
import com.kooo.evcam.R;
import com.kooo.evcam.v2.service.V2CameraForegroundService;

@Requires(target = StatusBarPlugin.class, version = StatusBarPlugin.VERSION)
public class V2StatusBarPlugin extends Service implements StatusBarPlugin, View.OnClickListener {
    private static final String TAG = "V2StatusBarPlugin";
    private static final String APP_PACKAGE = "com.kooo.evcam.v2";
    private static final int PLUGIN_ID = 132;
    private static final long REFRESH_DELAY_MS = 500L;

    private Context sysuiContext;
    private Context pluginContext;
    private DialogCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView recordingLabel;
    private CheckedTextView recordingSwitch;
    private boolean notificationSeen;
    private boolean notificationRecording;
    private String notificationStatus = "";

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        this.sysuiContext = sysuiContext;
        this.pluginContext = pluginContext;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void setDialogCallback(DialogCallback callback) {
        this.callback = callback;
    }

    @Override
    public int getID() {
        return PLUGIN_ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public String getDialogTitle() {
        return null;
    }

    @Override
    public int getDialogWidth() {
        Context context = safeContext();
        return context.getResources().getDimensionPixelSize(R.dimen.v2_status_bar_plugin_width);
    }

    @Override
    public int getDialogHeight() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @Override
    public View getDialogView() {
        Context context = safeContext();
        View view = LayoutInflater.from(context).inflate(R.layout.layout_v2_status_bar_plugin, null, false);
        statusText = view.findViewById(R.id.tv_status_bar_recording_state);
        recordingLabel = view.findViewById(R.id.tv_status_bar_recording_label);
        recordingSwitch = view.findViewById(R.id.switch_status_bar_recording);
        view.findViewById(R.id.header_status_bar_recording).setOnClickListener(this);
        view.findViewById(R.id.switch_status_bar_recording).setOnClickListener(this);
        view.findViewById(R.id.btn_status_bar_open).setOnClickListener(this);
        refreshState();
        return view;
    }

    @Override
    public void onStatusIconPosted(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String status = text == null ? "" : text.toString();
        notificationSeen = true;
        notificationStatus = status;
        notificationRecording = status.contains("rec=ON");
        mainHandler.post(this::refreshState);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.header_status_bar_recording || id == R.id.switch_status_bar_recording) {
            startServiceAction(V2CameraForegroundService.ACTION_TOGGLE_RECORDING_FROM_PLUGIN);
            scheduleRefresh();
        } else if (id == R.id.btn_status_bar_open) {
            openMainActivity();
        }
    }

    private void startServiceAction(String action) {
        Context context = hostContext();
        Intent intent = new Intent().setComponent(new ComponentName(APP_PACKAGE, "com.kooo.evcam.v2.plugin.V2StatusBarPluginReceiver"));
        intent.setAction(action);
        try {
            sendBroadcastToCurrentUser(context, intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "send plugin command failed", error);
        }
    }

    private void sendBroadcastToCurrentUser(Context context, Intent intent) {
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle = userHandleClass.getDeclaredConstructor(int.class).newInstance(currentUserId());
            Context.class.getMethod("sendBroadcastAsUser", Intent.class, userHandleClass).invoke(context, intent, userHandle);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "sendBroadcastAsUser failed, fallback to current context user", error);
            context.sendBroadcast(intent);
        }
    }

    private int currentUserId() {
        try {
            Class<?> activityManager = Class.forName("android.app.ActivityManager");
            Object userId = activityManager.getMethod("getCurrentUser").invoke(null);
            return userId instanceof Integer ? (Integer) userId : 0;
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "getCurrentUser failed", error);
            return 0;
        }
    }

    private void openMainActivity() {
        Context context = hostContext();
        Intent intent = new Intent().setComponent(new ComponentName(APP_PACKAGE, "com.kooo.evcam.v2.ui.V2MainActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivityAsCurrentUser(context, intent);
            if (callback != null) callback.dismissDialog();
        } catch (RuntimeException error) {
            Log.e(TAG, "open main activity failed", error);
        }
    }

    private void startActivityAsCurrentUser(Context context, Intent intent) {
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle = userHandleClass.getDeclaredConstructor(int.class).newInstance(currentUserId());
            Context.class.getMethod("startActivityAsUser", Intent.class, userHandleClass).invoke(context, intent, userHandle);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "startActivityAsUser failed, fallback to current context user", error);
            context.startActivity(intent);
        }
    }

    private void scheduleRefresh() {
        mainHandler.postDelayed(this::refreshState, REFRESH_DELAY_MS);
    }

    private void refreshState() {
        if (statusText == null || recordingSwitch == null) return;
        V2StatusBarStateStore.Snapshot snapshot = V2StatusBarStateStore.read(stateContext());
        boolean serviceReady = notificationSeen || snapshot.serviceReady;
        boolean recording = notificationSeen ? notificationRecording : snapshot.recording;
        recordingSwitch.setChecked(recording);
        if (recordingLabel != null) recordingLabel.setText("行车记录仪");
        statusText.setText(statusLabel(serviceReady, recording));
    }

    private String statusLabel(boolean serviceReady, boolean recording) {
        if (!serviceReady) return "未录制";
        return recording ? "循环录制中" : "未录制";
    }

    private Context safeContext() {
        Context context = stateContext();
        if (context != null) return context;
        return hostContext();
    }

    private Context stateContext() {
        if (pluginContext != null) return pluginContext;
        Context host = hostContextOrNull();
        if (host != null) {
            try {
                return host.createPackageContext(APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            } catch (PackageManager.NameNotFoundException error) {
                Log.w(TAG, "createPackageContext failed", error);
            }
        }
        return null;
    }

    private Context hostContext() {
        Context context = hostContextOrNull();
        return context != null ? context : this;
    }

    private Context hostContextOrNull() {
        if (sysuiContext != null) return sysuiContext;
        return getInitialApplication();
    }

    private static Context getInitialApplication() {
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object app = appGlobals.getMethod("getInitialApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "getInitialApplication failed", error);
            return null;
        }
    }
}
