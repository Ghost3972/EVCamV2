package com.flyme.auto.plugin.systemui

import android.service.notification.StatusBarNotification
import android.view.View
import com.flyme.plugin.Plugin
import com.flyme.plugin.annotations.ProvidesInterface

@ProvidesInterface(action = StatusBarPlugin.ACTION, version = StatusBarPlugin.VERSION)
interface StatusBarPlugin : Plugin {
    fun getDialogHeight(): Int

    fun getDialogTitle(): String?

    fun getDialogView(): View

    fun getDialogWidth(): Int

    override fun getID(): Int

    fun onDialogDismissed() = Unit

    fun onDialogShowed() = Unit

    fun onStatusIconPosted(sbn: StatusBarNotification) = Unit

    fun setDialogCallback(callback: DialogCallback?) = Unit

    interface DialogCallback {
        fun dismissDialog() = Unit
        fun updateDialogTitle(title: String?) = Unit
        fun updateHeightFromAnim(height: Int): Boolean = false
        fun isPanelExpended(): Boolean = false
    }

    companion object {
        const val ACTION: String = "com.flyme.auto.plugin.action.PLUGIN_STATUS_BAR"
        const val VERSION: Int = 1
    }
}
