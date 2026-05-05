package com.kooo.evcam.v2.ui.main

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2SettingsRepository

class V2TransparentBootActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        val startupPolicy = V2SettingsRepository.startupPolicy(this)
        V2AppLog.i(TAG, "onCreate autoRecord=${startupPolicy.autoStartRecording}")

        startCameraServiceFromForegroundActivity()
        if (startupPolicy.autoStartRecording) {
            requestAutoRecordingFromService()
            finishQuietly()
        } else {
            handler.postDelayed({ finishQuietly() }, FINISH_DELAY_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        V2AppLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun startCameraServiceFromForegroundActivity() {
        runCatching {
            V2CameraServiceCommands.start(this)
            V2AppLog.i(TAG, "foreground service start requested from transparent activity")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start foreground service failed", error)
        }
    }

    private fun requestAutoRecordingFromService() {
        runCatching {
            V2CameraServiceCommands.autoStartRecording(this)
            V2AppLog.i(TAG, "service auto recording requested")
        }.onFailure { error ->
            V2AppLog.e(TAG, "request service auto recording failed", error)
        }
    }

    private fun finishQuietly() {
        finish()
        disableFinishAnimation()
    }

    private fun disableFinishAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "V2TransparentBootActivity"
        private const val FINISH_DELAY_MS = 1_500L
    }
}
