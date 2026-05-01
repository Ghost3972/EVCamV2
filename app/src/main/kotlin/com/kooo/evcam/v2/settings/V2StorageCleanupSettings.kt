package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StorageCleaner
import com.kooo.evcam.v2.storage.V2StoragePathHelper

object V2StorageCleanupSettings {
    private const val PREFS = "evcam_v2_storage_cleanup_settings"
    private const val KEY_RESERVED_SPACE_GB = "reserved_space_gb"

    const val DEFAULT_RESERVED_SPACE_GB = 3
    private const val GB_BYTES = 1024L * 1024L * 1024L

    fun reservedSpaceGb(context: Context): Int = prefs(context).getInt(KEY_RESERVED_SPACE_GB, DEFAULT_RESERVED_SPACE_GB)

    fun reservedSpaceBytes(context: Context): Long = reservedSpaceGb(context).coerceAtLeast(0) * GB_BYTES

    fun setReservedSpaceGb(context: Context, value: Int) {
        val next = value.coerceIn(0, 1024)
        prefs(context).edit().putInt(KEY_RESERVED_SPACE_GB, next).apply()
        V2AppLog.i("V2StorageCleanupSettings", "reservedSpaceGb=$next")
    }

    fun summary(context: Context): String {
        val gb = reservedSpaceGb(context)
        val cleanup = if (gb <= 0) "关闭低空间滚动覆盖" else "可用空间低于 ${gb}GB 时，自动删除最旧 mp4 继续录制"
        return "$cleanup\n${V2StoragePathHelper.storageSummary(context)}"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
