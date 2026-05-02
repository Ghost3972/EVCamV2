package com.flyme.plugin

import android.content.ComponentName
import android.content.Context

interface Plugin {
    fun getID(): Int

    fun getVersion(): Int = -1

    fun onCreate(sysuiContext: Context, pluginContext: Context) = Unit

    fun onDestroy() = Unit

    fun getComponentName(): ComponentName =
        ComponentName.createRelative(javaClass.`package`!!.name, javaClass.name)
}
