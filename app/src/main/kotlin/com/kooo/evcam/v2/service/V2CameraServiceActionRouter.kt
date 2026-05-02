package com.kooo.evcam.v2.service

import android.content.Intent
import com.kooo.evcam.v2.settings.V2SettingsCategory

internal class V2CameraServiceActionRouter(
    private val scheduleAutoRecording: () -> Unit,
    private val settingsChanged: (String?) -> Unit,
    private val showFisheyePreview: (Int) -> Unit,
    private val hideFisheyePreview: () -> Unit,
    private val showBlindSpotPreview: (String) -> Unit,
    private val hideBlindSpotPreview: () -> Unit,
    private val toggleRecordingFromPlugin: () -> Unit,
    private val startEmergencyFromPlugin: () -> Unit,
    private val startEncoderStressTest: (Intent) -> Unit,
    private val stopEncoderStressTest: () -> Unit,
    private val startNativeWriterSmokeTest: (Intent) -> Unit,
    private val stopNativeWriterSmokeTest: () -> Unit,
    private val displayOff: (String?) -> Unit,
    private val displayOn: (String?) -> Unit,
) {
    fun route(intent: Intent?) {
        val action = intent?.action
        when {
            action == V2CameraForegroundService.ACTION_AUTO_START_RECORDING -> scheduleAutoRecording()
            action == V2CameraForegroundService.ACTION_SETTINGS_CHANGED -> settingsChanged(intent.getStringExtra(V2CameraForegroundService.EXTRA_SETTINGS_CATEGORY))
            action == V2CameraForegroundService.ACTION_REFRESH_CUSTOM_KEY -> settingsChanged(V2SettingsCategory.CUSTOM_KEY)
            action == V2CameraForegroundService.ACTION_REFRESH_BLIND_SPOT -> settingsChanged(V2SettingsCategory.BLIND_SPOT)
            action == V2CameraForegroundService.ACTION_REFRESH_FISHEYE -> settingsChanged(V2SettingsCategory.FISHEYE)
            action == V2CameraForegroundService.ACTION_REFRESH_WAKE_LOCK -> settingsChanged(V2SettingsCategory.WAKE_LOCK)
            action == V2CameraForegroundService.ACTION_SHOW_FISHEYE_PREVIEW -> showFisheyePreview(intent.getIntExtra(V2CameraForegroundService.EXTRA_CAMERA_INDEX, 0))
            action == V2CameraForegroundService.ACTION_HIDE_FISHEYE_PREVIEW -> hideFisheyePreview()
            action == V2CameraForegroundService.ACTION_SHOW_BLIND_SPOT_PREVIEW -> showBlindSpotPreview(intent.getStringExtra(V2CameraForegroundService.EXTRA_SIDE) ?: "left")
            action == V2CameraForegroundService.ACTION_HIDE_BLIND_SPOT_PREVIEW -> hideBlindSpotPreview()
            action == V2CameraForegroundService.ACTION_TOGGLE_RECORDING_FROM_PLUGIN -> toggleRecordingFromPlugin()
            action == V2CameraForegroundService.ACTION_START_EMERGENCY_FROM_PLUGIN -> startEmergencyFromPlugin()
            action == V2CameraForegroundService.ACTION_START_ENCODER_STRESS_TEST -> startEncoderStressTest(intent)
            action == V2CameraForegroundService.ACTION_STOP_ENCODER_STRESS_TEST -> stopEncoderStressTest()
            action == V2CameraForegroundService.ACTION_START_NATIVE_WRITER_SMOKE_TEST -> startNativeWriterSmokeTest(intent)
            action == V2CameraForegroundService.ACTION_STOP_NATIVE_WRITER_SMOKE_TEST -> stopNativeWriterSmokeTest()
            V2DisplayPowerActions.isDisplayOff(action) -> displayOff(action)
            V2DisplayPowerActions.isDisplayOn(action) -> displayOn(action)
        }
    }
}
