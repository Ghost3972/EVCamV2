package com.kooo.evcam.v2.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

internal class V2StatusBarContextResolver(
    private val appPackage: String,
    private val tag: String,
) {
    fun stateContext(pluginContext: Context?, hostContext: Context?): Context? {
        val appContext = if (hostContext != null) try {
            hostContext.createPackageContext(appPackage, Context.CONTEXT_IGNORE_SECURITY)
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(tag, "createPackageContext failed", error)
            null
        } else null
        return appContext ?: pluginContext
    }

    fun hostContextOrNull(sysuiContext: Context?): Context? = sysuiContext ?: initialApplication()

    private fun initialApplication(): Context? = try {
        val appGlobals = Class.forName("android.app.AppGlobals")
        appGlobals.getMethod("getInitialApplication").invoke(null) as? Context
    } catch (error: ReflectiveOperationException) {
        Log.w(tag, "getInitialApplication failed", error)
        null
    }
}
