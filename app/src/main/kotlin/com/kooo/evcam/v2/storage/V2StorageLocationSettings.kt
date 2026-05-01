package com.kooo.evcam.v2.storage

import android.content.Context

object V2StorageLocationSettings {
    private const val PREFS = "evcam_v2_storage_location_settings"
    private const val KEY_SELECTED_LOCATION = "selected_location"

    fun selectedLocation(context: Context): V2StoragePathHelper.StorageLocation {
        val name = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_LOCATION, V2StoragePathHelper.StorageLocation.INTERNAL.name)
            ?: V2StoragePathHelper.StorageLocation.INTERNAL.name
        return runCatching { V2StoragePathHelper.StorageLocation.valueOf(name) }.getOrDefault(V2StoragePathHelper.StorageLocation.INTERNAL)
    }

    fun setSelectedLocation(context: Context, location: V2StoragePathHelper.StorageLocation) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_LOCATION, location.name).apply()
    }
}
