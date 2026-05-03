package com.kooo.evcam.v2.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository

class V2CameraForegroundService : Service(), V2CameraEngine.Listener {
    companion object {
        const val ACTION_AUTO_START_RECORDING = "com.kooo.evcam.v2.action.AUTO_START_RECORDING"
        const val ACTION_REFRESH_CUSTOM_KEY = "com.kooo.evcam.v2.action.REFRESH_CUSTOM_KEY"
        const val ACTION_REFRESH_BLIND_SPOT = "com.kooo.evcam.v2.action.REFRESH_BLIND_SPOT"
        const val ACTION_REFRESH_FISHEYE = "com.kooo.evcam.v2.action.REFRESH_FISHEYE"
        const val ACTION_REFRESH_WAKE_LOCK = "com.kooo.evcam.v2.action.REFRESH_WAKE_LOCK"
        const val ACTION_SETTINGS_CHANGED = "com.kooo.evcam.v2.action.SETTINGS_CHANGED"
        const val ACTION_SHOW_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.SHOW_FISHEYE_PREVIEW"
        const val ACTION_HIDE_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.HIDE_FISHEYE_PREVIEW"
        const val ACTION_SHOW_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.SHOW_BLIND_SPOT_PREVIEW"
        const val ACTION_HIDE_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.HIDE_BLIND_SPOT_PREVIEW"
        const val ACTION_TOGGLE_RECORDING_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_TOGGLE_RECORDING"
        const val ACTION_START_EMERGENCY_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_START_EMERGENCY"
        const val EXTRA_CAMERA_INDEX = "camera_index"
        const val EXTRA_SIDE = "side"
        const val EXTRA_SETTINGS_CATEGORY = "settings_category"
        internal const val AUTO_START_RECORDING_DELAY_MS = 0L
        const val EMERGENCY_RECORDING_DURATION_MS = 15_000L
        @Volatile var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun service() = this@V2CameraForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var commandQueue: V2ServiceCommandQueue
    private lateinit var engine: V2CameraEngine
    private lateinit var keepAliveOrchestrator: V2KeepAliveOrchestrator
    private lateinit var autoRecordingController: V2AutoRecordingController
    private var uiStatusListener: ((String) -> Unit)? = null
    private var uiEmergencyRecordingListener: ((Boolean, Long) -> Unit)? = null
    private lateinit var uiVisibilityOrchestrator: V2UiVisibilityOrchestrator
    private lateinit var previewCoordinator: V2PreviewSurfaceCoordinator
    private lateinit var readinessOrchestrator: V2CameraReadinessOrchestrator
    private lateinit var customKeyController: V2CustomKeyController
    private lateinit var fisheyePreviewController: V2FisheyePreviewController
    private lateinit var blindSpotController: V2BlindSpotController
    private lateinit var avoidanceController: V2AvoidanceController
    private lateinit var recordingOrchestrator: V2RecordingOrchestrator
    private lateinit var statusReporter: V2ServiceStatusReporter
    private lateinit var displayPowerController: V2DisplayPowerController
    private lateinit var displayPowerOrchestrator: V2DisplayPowerOrchestrator
    private lateinit var cameraWatchdog: V2CameraWatchdog
    private lateinit var watchdogRestartOrchestrator: V2WatchdogRestartOrchestrator
    private lateinit var settingsRuntimeCoordinator: V2SettingsRuntimeCoordinator
    private lateinit var actionRouter: V2CameraServiceActionRouter
    private lateinit var lifecycleOrchestrator: V2CameraServiceLifecycleOrchestrator
    private var compositePreviewSurface: Surface? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        V2AppLog.init(this)
        initializeCoreRuntime()
        keepAliveOrchestrator.recordCreated()
        initializePreviewAndReadiness()
        initializeWatchdogAndPowerOrchestrators()
        initializeFeatureControllers()
        initializeServiceRouting()
        initializeLifecycleOrchestrator()
        lifecycleOrchestrator.startRuntime()
    }

    private fun initializeCoreRuntime() {
        commandQueue = V2ServiceCommandQueue(mainHandler)
        displayPowerController = V2DisplayPowerController(
            service = this,
            handler = mainHandler,
            systemInteractive = isSystemInteractive(),
            onDisplayOff = { action -> commandQueue.dispatch("displayOff:$action") { displayPowerOrchestrator.handleDisplayOff(action) } },
            onDisplayOn = { action -> commandQueue.dispatch("displayOn:$action") { displayPowerOrchestrator.handleDisplayOn(action) } },
        )
        V2AppLog.i("V2CameraService", "onCreate autoRecord=${startupPolicy().autoStartRecording} displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()} stableApi=ecarx_display_power")
        engine = V2CameraEngine(this, this)
        autoRecordingController = V2AutoRecordingController(
            service = this,
            handler = mainHandler,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isAutoStartEnabled = { startupPolicy().autoStartRecording && !avoidanceController.isActive },
            isRecording = { engine.isRecording() },
            startRecording = { commandQueue.dispatch("autoStartRecording") { recordingOrchestrator.startAutoRecordingIfAllowed() } },
            showToast = { statusReporter.showToast(it) }
        )
        keepAliveOrchestrator = V2KeepAliveOrchestrator(
            service = this,
            isDisplayPowerOn = { isDisplayPowerOn() },
            startupPolicy = { startupPolicy() },
            keepAlivePolicy = { keepAlivePolicy() },
            releaseCamerasIfSystemAlreadyNonInteractive = { reason -> displayPowerOrchestrator.releaseCamerasIfSystemAlreadyNonInteractive(reason) },
        )
    }

    private fun initializePreviewAndReadiness() {
        uiVisibilityOrchestrator = V2UiVisibilityOrchestrator(
            service = this,
            onVisibilityChanged = { readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
        statusReporter = V2ServiceStatusReporter(
            service = this,
            statusText = { engine.statusText() },
            isRecording = { engine.isNormalRecording() },
            isAnyRecording = { engine.isRecording() },
            isEmergencyRecordingActive = { recordingOrchestrator.emergencyRecordingActive },
            emergencyRecordingEndsAtWallClockMs = { recordingOrchestrator.emergencyRecordingEndsAtWallClockMs() },
            notifyUiStatus = { status -> uiStatusListener?.invoke(status) },
        )
        previewCoordinator = V2PreviewSurfaceCoordinator(
            slotCount = V2_CAMERA_SLOT_COUNT,
            isDisplayPowerOn = { isDisplayPowerOn() },
            attachNative = { index, surface, owner ->
                engine.attachPreviewSurface(
                    index = index,
                    surface = surface,
                    applyFisheye = owner != V2PreviewLeaseManager.Owner.BLIND_SPOT,
                    applyNativeTransform = owner != V2PreviewLeaseManager.Owner.MAIN || index < 2,
                )
            },
            detachNative = { index -> engine.detachPreviewSurface(index) },
            onLeasesChanged = { readinessOrchestrator.updatePreviewRenderingEnabled() },
        )
        readinessOrchestrator = V2CameraReadinessOrchestrator(
            engine = engine,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isUiVisible = { uiVisibilityOrchestrator.isVisible },
            hasOverlayPreview = { previewCoordinator.hasOverlayOwner() },
            restoreMainPreviews = { restorePreviewSurfaces() },
            resetWatchdog = { reason -> cameraWatchdog.reset(reason) },
            syncRecordingStateAndUi = { statusReporter.publishSnapshot("readiness") },
        )
    }

    private fun initializeWatchdogAndPowerOrchestrators() {
        cameraWatchdog = V2CameraWatchdog(
            handler = mainHandler,
            engine = engine,
            isDisplayPowerOn = { isDisplayPowerOn() },
            shouldExpectPreviewRendering = { recording -> readinessOrchestrator.shouldExpectPreviewRendering(recording) },
            onRestartRequired = { reason -> commandQueue.dispatch("watchdogRestart:$reason") { watchdogRestartOrchestrator.restartCameras(reason) } }
        )
        watchdogRestartOrchestrator = V2WatchdogRestartOrchestrator(
            engine = engine,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isAvoidanceActive = { avoidanceController.isActive },
            restoreMainPreviews = { restorePreviewSurfaces() },
            publishSnapshot = { reason -> statusReporter.publishSnapshot(reason) },
            dispatchDelayed = { name, delayMs, block -> commandQueue.dispatchDelayed(name, delayMs, V2WatchdogRestartOrchestrator.RECORDING_RESTART_TOKEN, block) },
        )
        displayPowerOrchestrator = V2DisplayPowerOrchestrator(
            displayPowerController = displayPowerController,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isAutoRecordingEnabled = { startupPolicy().autoStartRecording },
            isRecording = { engine.isRecording() },
            isAvoidanceActive = { avoidanceController.isActive },
            avoidanceTarget = { avoidanceController.activeTarget },
            stopRecordingAndReleaseCameras = { reason -> engine.stopRecordingAndReleaseCameras(reason) },
            setCameraAccessAllowed = { allowed -> engine.setCameraAccessAllowed(allowed) },
            startRecording = { engine.startRecording() },
            publishSnapshot = { reason -> statusReporter.publishSnapshot(reason) },
            dispatchDelayed = { name, delayMs, block -> commandQueue.dispatchDelayed(name, delayMs, V2DisplayPowerOrchestrator.DISPLAY_ON_RECORDING_RESTORE_TOKEN, block) },
            resetWatchdog = { reason -> cameraWatchdog.reset(reason) },
            startWatchdog = { cameraWatchdog.start() },
            cancelAutoRecording = { autoRecordingController.cancelPending() },
            scheduleAutoRecording = { autoRecordingController.scheduleIfEnabled() },
            clearAvoidance = { reason -> avoidanceController.clear(reason) },
            hideBlindSpot = { blindSpotController.hide() },
            hideFisheye = { fisheyePreviewController.hide() },
            restoreMainPreviews = { restorePreviewSurfaces() },
            saveLog = { V2AppLog.saveToPersistentLog(this) },
        )
    }

    private fun initializeFeatureControllers() {
        fisheyePreviewController = V2FisheyePreviewController(
            service = this,
            engine = engine,
            previewSurfaces = previewCoordinator.surfaces,
            isDisplayPowerOn = { isDisplayPowerOn() },
            showToast = { statusReporter.showToast(it) }
        )
        customKeyController = V2CustomKeyController(
            context = this,
            handler = mainHandler,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isUiVisible = { uiVisibilityOrchestrator.isVisible },
            hideUi = { uiVisibilityOrchestrator.hideForAvoidance() },
            showUi = { uiVisibilityOrchestrator.showFromCustomKey() },
        )
        blindSpotController = V2BlindSpotController(
            context = this,
            handler = mainHandler,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isUiVisible = { uiVisibilityOrchestrator.isVisible },
            shouldAvoidWindow = { avoidanceController.shouldAvoidBlindSpotWindow() },
            avoidanceTarget = { avoidanceController.activeTarget ?: avoidanceController.currentTarget() },
            previewIndexForSide = { side -> engine.previewIndexForPosition(side) },
            previewDescription = { index -> engine.previewDescription(index) },
            previewInputSize = { index -> engine.previewInputSize(index) },
            attachPreview = { index, surface -> attachBlindSpotPreviewSurface(index, surface) },
            detachPreview = { index -> detachBlindSpotPreviewSurface(index) },
            restoreMainPreview = { index -> restoreMainPreviewSurface(index) },
            hideFisheyePreview = { fisheyePreviewController.hide() },
            hideUi = { uiVisibilityOrchestrator.hideForAvoidance() },
            restoreUi = { uiVisibilityOrchestrator.restoreFromBlindSpot() },
            renderedFrames = { index -> engine.previewRenderedFrames(index) },
            showToast = { message -> statusReporter.showToast(message) },
        )
        avoidanceController = V2AvoidanceController(
            context = this,
            handler = mainHandler,
            foregroundAppMonitor = V2ForegroundAppMonitor(this),
            isDisplayPowerOn = { isDisplayPowerOn() },
            isRecording = { engine.isRecording() },
            isUiVisible = { uiVisibilityOrchestrator.isVisible },
            onHideBlindSpot = { blindSpotController.cancelAndHideForAvoidance() },
            onHideFisheye = { fisheyePreviewController.hide() },
            onCancelAutoRecording = { autoRecordingController.cancelPending() },
            onHideUi = { uiVisibilityOrchestrator.hideForAvoidance() },
            onStopRecording = {
                engine.stopRecording()
                statusReporter.publishSnapshot("avoidance_stop_recording")
            },
            onRestoreRecording = {
                engine.startRecording()
                statusReporter.publishSnapshot("avoidance_restore_recording")
            },
            onRestoreUi = { uiVisibilityOrchestrator.restoreFromAvoidance() },
            onScheduleAutoRecording = { autoRecordingController.scheduleIfEnabled() },
            showToast = { statusReporter.showToast(it) }
        )
    }

    private fun initializeServiceRouting() {
        recordingOrchestrator = V2RecordingOrchestrator(
            handler = mainHandler,
            engine = engine,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isSystemInteractive = { isSystemInteractive() },
            isAvoidanceActive = { avoidanceController.isActive },
            avoidanceTarget = { avoidanceController.activeTarget },
            publishSnapshot = { reason -> statusReporter.publishSnapshot(reason) },
            notifyEmergencyRecordingState = { active, endsAtMs -> uiEmergencyRecordingListener?.invoke(active, endsAtMs) },
            showToast = { statusReporter.showToast(it) },
        )
        settingsRuntimeCoordinator = V2SettingsRuntimeCoordinator(
            handler = mainHandler,
            loadSnapshot = { V2SettingsRepository.currentSnapshot(this) },
            loadFisheye = { V2SettingsRepository.fisheyeConfig(this) },
            loadAvoidance = { V2SettingsRepository.avoidanceConfig(this) },
            loadBlindSpot = { V2SettingsRepository.blindSpotConfig(this) },
            loadCustomKey = { V2SettingsRepository.customKeyConfig(this) },
            applyFisheye = { fisheye -> engine.applyFisheyeSettings(fisheye) },
            restartBlindSpot = { config -> blindSpotController.restartObserver(config) },
            restartCustomKey = { config -> customKeyController.restart(config) },
            refreshWakeLock = { keepAliveOrchestrator.refreshWakeLock() },
            updateAvoidance = { config -> avoidanceController.updateConfig(config) },
        )
        actionRouter = V2CameraServiceActionRouter(
            scheduleAutoRecording = { commandQueue.dispatch("action:autoStartRecording") { autoRecordingController.scheduleIfEnabled() } },
            settingsChanged = { category -> commandQueue.dispatch("action:settingsChanged:$category") { settingsRuntimeCoordinator.onSettingsChanged(category) } },
            showFisheyePreview = { index -> commandQueue.dispatch("action:showFisheye:$index") { fisheyePreviewController.show(index) } },
            hideFisheyePreview = { commandQueue.dispatch("action:hideFisheye") { fisheyePreviewController.hide() } },
            showBlindSpotPreview = { side -> commandQueue.dispatch("action:showBlindSpot:$side") { blindSpotController.showPreview(side) } },
            hideBlindSpotPreview = { commandQueue.dispatch("action:hideBlindSpot") { blindSpotController.hide() } },
            toggleRecordingFromPlugin = { commandQueue.dispatch("action:toggleRecording") { recordingOrchestrator.toggleRecordingFromPlugin() } },
            startEmergencyFromPlugin = { commandQueue.dispatch("action:startEmergency") { recordingOrchestrator.startEmergencyRecordingFromPlugin() } },
            displayOff = { action -> commandQueue.dispatch("action:displayOff:$action") { displayPowerOrchestrator.handleDisplayOff(action) } },
            displayOn = { action -> commandQueue.dispatch("action:displayOn:$action") { displayPowerOrchestrator.handleDisplayOn(action) } },
        )
    }

    private fun initializeLifecycleOrchestrator() {
        lifecycleOrchestrator = V2CameraServiceLifecycleOrchestrator(
            service = this,
            engine = engine,
            displayPowerController = displayPowerController,
            customKeyController = customKeyController,
            blindSpotController = blindSpotController,
            fisheyePreviewController = fisheyePreviewController,
            avoidanceController = avoidanceController,
            cameraWatchdog = cameraWatchdog,
            keepAliveOrchestrator = keepAliveOrchestrator,
            statusReporter = statusReporter,
            autoRecordingController = autoRecordingController,
            isDisplayPowerOn = { isDisplayPowerOn() },
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf() },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2BroadcastLogger.logServiceStart("V2CameraService", intent, flags, startId)
        keepAliveOrchestrator.recordStartCommand(intent?.action)
        actionRouter.route(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleOrchestrator.destroyRuntime()
        isRunning = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        keepAliveOrchestrator.handleTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    fun toggleRecording(): Boolean {
        return recordingOrchestrator.toggleRecording()
    }
    fun isRecording(): Boolean = engine.isRecording()
    fun isNormalRecording(): Boolean = engine.isNormalRecording()
    fun statusText(): String = engine.statusText()
    fun isPreviewPausedByAvoidance(): Boolean = false
    fun ensureReadyAfterPermissions() {
        readinessOrchestrator.ensureReadyAfterPermissions()
    }
    fun previewInputSizeLabel(index: Int): String = engine.previewInputSizeLabel(index)
    fun previewInputSize(index: Int): android.util.Size? = engine.previewInputSize(index)
    fun compositePreviewSizeLabel(): String = engine.compositePreviewSizeLabel()
    fun attachCompositePreviewSurface(surface: Surface) {
        compositePreviewSurface = surface
        engine.attachCompositePreviewSurface(surface)
    }
    fun detachCompositePreviewSurface() {
        compositePreviewSurface = null
        engine.detachCompositePreviewSurface()
    }
    fun attachPreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachMain(index, surface)
    }
    fun detachPreviewSurface(index: Int) {
        previewCoordinator.detachMain(index)
    }
    internal fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachFisheye(index, surface)
    }
    internal fun detachFisheyePreviewSurface(index: Int) {
        previewCoordinator.detachFisheye(index)
    }
    internal fun canShowFisheyePreview(index: Int): Boolean {
        if (blindSpotController.activeCameraIndex >= 0 || previewCoordinator.isBlindSpotOwner(index)) {
            V2AppLog.i("V2CameraService", "fisheye preview denied: blind spot active index=$index blindSpotIndex=${blindSpotController.activeCameraIndex}")
            return false
        }
        return true
    }
    private fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        previewCoordinator.attachBlindSpot(index, surface)
    }
    private fun detachBlindSpotPreviewSurface(index: Int) {
        previewCoordinator.detachBlindSpot(index)
    }

    private fun restoreMainPreviewSurface(index: Int) {
        previewCoordinator.restoreMain(index)
    }

    private fun restorePreviewSurfaces() {
        compositePreviewSurface?.takeIf { it.isValid }?.let { engine.attachCompositePreviewSurface(it) }
        previewCoordinator.restoreAllMain()
    }
    fun startRecording() {
        recordingOrchestrator.startRecording()
    }
    fun stopRecording() { recordingOrchestrator.stopRecording() }

    fun startEmergencyRecording(durationMs: Long = EMERGENCY_RECORDING_DURATION_MS, onStateChanged: ((Boolean) -> Unit)? = null): Boolean {
        return recordingOrchestrator.startEmergencyRecording(durationMs, onStateChanged)
    }
    fun shutdownFromUi() {
        lifecycleOrchestrator.shutdownFromUi()
    }
    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        uiStatusListener = listener
        listener?.invoke(engine.statusText())
    }

    fun setUiEmergencyRecordingListener(listener: ((Boolean, Long) -> Unit)?) {
        uiEmergencyRecordingListener = listener
        listener?.invoke(recordingOrchestrator.emergencyRecordingActive, recordingOrchestrator.emergencyRecordingEndsAtMs)
    }
    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        uiVisibilityOrchestrator.setVisible(visible, hideListener)
    }

    private fun isSystemInteractive(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun isDisplayPowerOn(): Boolean = ::displayPowerController.isInitialized && displayPowerController.isOn()

    private fun startupPolicy() = V2SettingsRepository.startupPolicy(this)

    private fun keepAlivePolicy() = V2SettingsRepository.keepAlivePolicy(this)

    override fun onStatusChanged(status: String) {
        statusReporter.onStatusChanged(status)
    }
}
