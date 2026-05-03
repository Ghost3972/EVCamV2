package com.kooo.evcam.v2

import android.app.Application
import android.util.Log
import androidx.work.Configuration

class V2EVCamApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
