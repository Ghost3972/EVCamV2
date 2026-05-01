package com.kooo.evcam.v2.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.v2.service.V2CameraForegroundService;

public class V2StatusBarPluginReceiver extends BroadcastReceiver {
    private static final String TAG = "V2StatusBarReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!V2CameraForegroundService.ACTION_TOGGLE_RECORDING_FROM_PLUGIN.equals(action)) {
            return;
        }

        Intent serviceIntent = new Intent(context, V2CameraForegroundService.class);
        serviceIntent.setAction(action);
        try {
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (RuntimeException error) {
            Log.e(TAG, "start service from plugin broadcast failed", error);
        }
    }
}
