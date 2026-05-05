package com.kooo.evcam.v2.service.lifecycle

import android.app.Service
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.avoidance.V2AvoidanceController
import com.kooo.evcam.v2.service.camera.V2CameraEngine
import com.kooo.evcam.v2.service.camera.V2CameraReadinessOrchestrator
import com.kooo.evcam.v2.service.commands.V2CameraServiceActionRouter
import com.kooo.evcam.v2.service.commands.V2ServiceCommandQueue
import com.kooo.evcam.v2.service.display.V2DisplayPowerController
import com.kooo.evcam.v2.service.display.V2DisplayPowerOrchestrator
import com.kooo.evcam.v2.service.keepalive.V2KeepAliveOrchestrator
import com.kooo.evcam.v2.service.preview.V2BlindSpotController
import com.kooo.evcam.v2.service.preview.V2CameraServicePreviewFacade
import com.kooo.evcam.v2.service.preview.V2FisheyePreviewController
import com.kooo.evcam.v2.service.preview.V2PreviewSurfaceCoordinator
import com.kooo.evcam.v2.service.recording.V2AutoRecordingController
import com.kooo.evcam.v2.service.recording.V2RecordingOrchestrator
import com.kooo.evcam.v2.service.settings.V2SettingsRuntimeCoordinator
import com.kooo.evcam.v2.service.status.V2ServiceStatusReporter
import com.kooo.evcam.v2.service.status.V2UiVisibilityOrchestrator
import com.kooo.evcam.v2.service.vhal.V2CustomKeyController
import com.kooo.evcam.v2.settings.V2SettingsRepository

internal class V2CameraServiceRuntimeGraph(
    val service: V2CameraForegroundService,
    val engineListener: V2CameraEngine.Listener,
) {
    val mainHandler = Handler(Looper.getMainLooper())
    lateinit var commandQueue: V2ServiceCommandQueue
    lateinit var engine: V2CameraEngine
    lateinit var keepAliveOrchestrator: V2KeepAliveOrchestrator
    lateinit var autoRecordingController: V2AutoRecordingController
    var uiStatusListener: ((String) -> Unit)? = null
    var uiEmergencyRecordingListener: ((Boolean, Long) -> Unit)? = null
    lateinit var uiVisibilityOrchestrator: V2UiVisibilityOrchestrator
    lateinit var previewCoordinator: V2PreviewSurfaceCoordinator
    lateinit var previewFacade: V2CameraServicePreviewFacade
    lateinit var readinessOrchestrator: V2CameraReadinessOrchestrator
    lateinit var customKeyController: V2CustomKeyController
    lateinit var fisheyePreviewController: V2FisheyePreviewController
    lateinit var blindSpotController: V2BlindSpotController
    lateinit var avoidanceController: V2AvoidanceController
    lateinit var recordingOrchestrator: V2RecordingOrchestrator
    lateinit var statusReporter: V2ServiceStatusReporter
    lateinit var displayPowerController: V2DisplayPowerController
    lateinit var displayPowerOrchestrator: V2DisplayPowerOrchestrator
    lateinit var settingsRuntimeCoordinator: V2SettingsRuntimeCoordinator
    lateinit var actionRouter: V2CameraServiceActionRouter
    lateinit var lifecycleOrchestrator: V2CameraServiceLifecycleOrchestrator

    fun isSystemInteractive(): Boolean {
        val powerManager = service.getSystemService(Service.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    fun isDisplayPowerOn(): Boolean = ::displayPowerController.isInitialized && displayPowerController.isOn()

    fun startupPolicy() = V2SettingsRepository.startupPolicy(service)

    fun keepAlivePolicy() = V2SettingsRepository.keepAlivePolicy(service)
}
