package com.kooo.evcam.v2.plugin

import android.content.Context

object V2StatusBarStateStore {
    private const val PREFS = "v2_status_bar_plugin"
    private const val KEY_SERVICE_READY = "service_ready"
    private const val KEY_RECORDING = "recording"
    private const val KEY_EMERGENCY = "emergency"
    private const val KEY_STATUS = "status"
    private const val KEY_EMERGENCY_ENDS_AT = "emergency_ends_at"
    private const val KEY_UPDATED_AT = "updated_at"

    @JvmStatic
    fun update(context: Context, serviceReady: Boolean, recording: Boolean, emergency: Boolean, status: String?, emergencyEndsAtMs: Long = 0L) {
        prefs(context).edit()
            .putBoolean(KEY_SERVICE_READY, serviceReady)
            .putBoolean(KEY_RECORDING, recording)
            .putBoolean(KEY_EMERGENCY, emergency)
            .putString(KEY_STATUS, status.orEmpty())
            .putLong(KEY_EMERGENCY_ENDS_AT, if (emergency) emergencyEndsAtMs else 0L)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    @JvmStatic
    fun read(context: Context?): Snapshot {
        if (context == null) return Snapshot.empty()
        val prefs = prefs(context)
        return Snapshot(
            serviceReady = prefs.getBoolean(KEY_SERVICE_READY, false),
            recording = prefs.getBoolean(KEY_RECORDING, false),
            emergency = prefs.getBoolean(KEY_EMERGENCY, false),
            status = prefs.getString(KEY_STATUS, "").orEmpty(),
            emergencyEndsAtMs = prefs.getLong(KEY_EMERGENCY_ENDS_AT, 0L),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        @JvmField val serviceReady: Boolean,
        @JvmField val recording: Boolean,
        @JvmField val emergency: Boolean,
        @JvmField val status: String,
        @JvmField val emergencyEndsAtMs: Long,
    ) {
        companion object {
            fun empty() = Snapshot(false, false, false, "", 0L)
        }
    }
}
