package com.flyme.plugin;

import android.content.ComponentName;
import android.content.Context;

public interface Plugin {
    int getID();

    default int getVersion() {
        return -1;
    }

    default void onCreate(Context sysuiContext, Context pluginContext) {
    }

    default void onDestroy() {
    }

    default ComponentName getComponentName() {
        return ComponentName.createRelative(getClass().getPackage().getName(), getClass().getName());
    }
}
