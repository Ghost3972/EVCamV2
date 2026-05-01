package com.kooo.evcam.v2.plugin;

import android.content.Context;
import android.content.SharedPreferences;

public final class V2StatusBarStateStore {
    private static final String PREFS = "v2_status_bar_plugin";
    private static final String KEY_SERVICE_READY = "service_ready";
    private static final String KEY_RECORDING = "recording";
    private static final String KEY_EMERGENCY = "emergency";
    private static final String KEY_STATUS = "status";
    private static final String KEY_UPDATED_AT = "updated_at";

    private V2StatusBarStateStore() {
    }

    public static void update(Context context, boolean serviceReady, boolean recording, boolean emergency, String status) {
        prefs(context).edit()
                .putBoolean(KEY_SERVICE_READY, serviceReady)
                .putBoolean(KEY_RECORDING, recording)
                .putBoolean(KEY_EMERGENCY, emergency)
                .putString(KEY_STATUS, status == null ? "" : status)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static Snapshot read(Context context) {
        if (context == null) {
            return Snapshot.empty();
        }
        SharedPreferences prefs = prefs(context);
        return new Snapshot(
                prefs.getBoolean(KEY_SERVICE_READY, false),
                prefs.getBoolean(KEY_RECORDING, false),
                prefs.getBoolean(KEY_EMERGENCY, false),
                prefs.getString(KEY_STATUS, "")
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Snapshot {
        public final boolean serviceReady;
        public final boolean recording;
        public final boolean emergency;
        public final String status;

        private Snapshot(boolean serviceReady, boolean recording, boolean emergency, String status) {
            this.serviceReady = serviceReady;
            this.recording = recording;
            this.emergency = emergency;
            this.status = status == null ? "" : status;
        }

        private static Snapshot empty() {
            return new Snapshot(false, false, false, "");
        }
    }
}
