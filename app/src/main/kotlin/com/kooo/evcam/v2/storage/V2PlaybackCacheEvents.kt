package com.kooo.evcam.v2.storage

import android.content.Context
import android.content.Intent

object V2PlaybackCacheEvents {
    const val ACTION_CHANGED = "com.kooo.evcam.v2.action.PLAYBACK_CACHE_CHANGED"
    const val EXTRA_REASON = "reason"
    const val EXTRA_PATH = "path"

    fun notifyChanged(context: Context, reason: String, path: String? = null) {
        val appContext = context.applicationContext
        val intent = Intent(ACTION_CHANGED)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_REASON, reason)
        if (!path.isNullOrBlank()) intent.putExtra(EXTRA_PATH, path)
        appContext.sendBroadcast(intent)
    }
}
