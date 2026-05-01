package com.flyme.auto.plugin.systemui;

import android.service.notification.StatusBarNotification;
import android.view.View;

import com.flyme.plugin.Plugin;
import com.flyme.plugin.annotations.ProvidesInterface;

@ProvidesInterface(action = StatusBarPlugin.ACTION, version = StatusBarPlugin.VERSION)
public interface StatusBarPlugin extends Plugin {
    String ACTION = "com.flyme.auto.plugin.action.PLUGIN_STATUS_BAR";
    int VERSION = 1;

    int getDialogHeight();

    String getDialogTitle();

    View getDialogView();

    int getDialogWidth();

    @Override
    int getID();

    default void onDialogDismissed() {
    }

    default void onDialogShowed() {
    }

    default void onStatusIconPosted(StatusBarNotification sbn) {
    }

    default void setDialogCallback(DialogCallback callback) {
    }

    interface DialogCallback {
        default void dismissDialog() {
        }

        default void updateDialogTitle(String title) {
        }

        default boolean updateHeightFromAnim(int height) {
            return false;
        }

        default boolean isPanelExpended() {
            return false;
        }
    }
}
