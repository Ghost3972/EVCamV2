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
import android.widget.Toast
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.plugin.V2StatusBarStateStore
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.storage.V2PlaybackCacheMaintainer
import com.kooo.evcam.v2.ui.V2MainActivity

class V2CameraForegroundService : Service(), V2CameraEngine.Listener {
    companion object {
        const val ACTION_AUTO_START_RECORDING = "com.kooo.evcam.v2.action.AUTO_START_RECORDING"
        const val ACTION_REFRESH_CUSTOM_KEY = "com.kooo.evcam.v2.action.REFRESH_CUSTOM_KEY"
        const val ACTION_REFRESH_BLIND_SPOT = "com.kooo.evcam.v2.action.REFRESH_BLIND_SPOT"
        const val ACTION_REFRESH_FISHEYE = "com.kooo.evcam.v2.action.REFRESH_FISHEYE"
        const val ACTION_REFRESH_WAKE_LOCK = "com.kooo.evcam.v2.action.REFRESH_WAKE_LOCK"
        const val ACTION_SHOW_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.SHOW_FISHEYE_PREVIEW"
        const val ACTION_HIDE_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.HIDE_FISHEYE_PREVIEW"
        const val ACTION_SHOW_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.SHOW_BLIND_SPOT_PREVIEW"
        const val ACTION_HIDE_BLIND_SPOT_PREVIEW = "com.kooo.evcam.v2.action.HIDE_BLIND_SPOT_PREVIEW"
        const val ACTION_TOGGLE_RECORDING_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_TOGGLE_RECORDING"
        const val ACTION_START_EMERGENCY_FROM_PLUGIN = "com.kooo.evcam.v2.action.PLUGIN_START_EMERGENCY"
        const val EXTRA_CAMERA_INDEX = "camera_index"
        const val EXTRA_SIDE = "side"
        internal const val AUTO_START_RECORDING_DELAY_MS = 0L
        private const val WATCHDOG_RECORDING_RESTART_DELAY_MS = 3_000L
        private const val SERVICE_RESTART_DELAY_MS = 1_000L
        private const val KEEP_ALIVE_CHAIN_INTERVAL_MS = 60_000L
        private const val STATUS_BAR_STATE_INTERVAL_MS = 15_000L
        const val EMERGENCY_RECORDING_DURATION_MS = 15_000L
    }

    inner class LocalBinder : Binder() {
        fun service() = this@V2CameraForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: V2CameraEngine
    private val notificationHelper = V2CameraNotificationHelper(this)
    private val wakeLockHolder = V2WakeLockHolder(this)
    private val autoRecordingController = V2AutoRecordingController(
        service = this,
        handler = mainHandler,
        isDisplayPowerOn = { isDisplayPowerOn() },
        isAutoStartEnabled = { V2StartupSettings.isAutoStartRecording(this) && !avoidanceController.isActive },
        isRecording = { engine.isRecording() },
        startRecording = { startAutoRecordingIfAllowed() },
        showToast = { showServiceToast(it) }
    )
    private var uiStatusListener: ((String) -> Unit)? = null
    private var uiEmergencyRecordingListener: ((Boolean, Long) -> Unit)? = null
    private var uiHideListener: (() -> Unit)? = null
    private var uiVisible = false
    private var manualShutdown = false
    private lateinit var previewLeaseManager: V2PreviewLeaseManager
    private lateinit var customKeyController: V2CustomKeyController
    private lateinit var fisheyePreviewController: V2FisheyePreviewController
    private lateinit var blindSpotController: V2BlindSpotController
    private lateinit var avoidanceController: V2AvoidanceController
    private var lastToastText: String? = null
    private var lastToastMs = 0L
    private var lastNotificationText: String? = null
    private var lastNotificationMs = 0L
    private var lastNotificationRecording: Boolean? = null
    private var lastNotificationEmergency: Boolean? = null
    private var lastKeepAliveChainMs = 0L
    private var lastUiStatusText: String? = null
    private var lastStatusBarStatus: String? = null
    private var lastStatusBarRecording: Boolean? = null
    private var lastStatusBarEmergency: Boolean? = null
    private var lastStatusBarUpdateMs = 0L
    private var emergencyRecordingActive = false
    private var emergencyRecordingEndsAtMs = 0L
    private var resumeNormalRecordingAfterEmergency = false
    private var emergencyRecordingStopRunnable: Runnable? = null
    private var resumeRecordingAfterDisplayOn = false
    private val previewSurfaces = arrayOfNulls<Surface>(4)
    private lateinit var displayPowerController: V2DisplayPowerController
    private lateinit var cameraWatchdog: V2CameraWatchdog

    override fun onCreate() {
        super.onCreate()
        V2AppLog.init(this)
        V2KeepAliveStatus.recordServiceCreated(this)
        V2KeepAliveStatus.recordTrigger(this, "service", "on_create")
        displayPowerController = V2DisplayPowerController(
            service = this,
            handler = mainHandler,
            systemInteractive = isSystemInteractive(),
            onDisplayOff = { action -> handleDisplayOff(action) },
            onDisplayOn = { action -> handleDisplayOn(action) },
        )
        V2AppLog.i("V2CameraService", "onCreate autoRecord=${V2StartupSettings.isAutoStartRecording(this)} displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()} stableApi=ecarx_display_power")
        engine = V2CameraEngine(this, this)
        previewLeaseManager = V2PreviewLeaseManager(
            slotCount = previewSurfaces.size,
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
            onLeasesChanged = { updatePreviewRenderingEnabled() },
        )
        cameraWatchdog = V2CameraWatchdog(
            handler = mainHandler,
            engine = engine,
            isDisplayPowerOn = { isDisplayPowerOn() },
            shouldExpectPreviewRendering = { recording -> shouldExpectPreviewRendering(recording) },
            onRestartRequired = { reason -> restartCamerasFromWatchdog(reason) }
        )
        fisheyePreviewController = V2FisheyePreviewController(
            service = this,
            engine = engine,
            previewSurfaces = previewSurfaces,
            isDisplayPowerOn = { isDisplayPowerOn() },
            showToast = { showServiceToast(it) }
        )
        customKeyController = V2CustomKeyController(
            context = this,
            handler = mainHandler,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isUiVisible = { uiVisible },
            hideUi = { uiHideListener?.invoke() },
            showUi = { showUiFromCustomKey() },
        )
        blindSpotController = V2BlindSpotController(
            context = this,
            handler = mainHandler,
            isDisplayPowerOn = { isDisplayPowerOn() },
            isUiVisible = { uiVisible },
            shouldAvoidWindow = { avoidanceController.shouldAvoidBlindSpotWindow() },
            avoidanceTarget = { avoidanceController.activeTarget ?: avoidanceController.currentTarget() },
            previewIndexForSide = { side -> engine.previewIndexForPosition(side) },
            previewDescription = { index -> engine.previewDescription(index) },
            previewInputSize = { index -> engine.previewInputSize(index) },
            attachPreview = { index, surface -> attachBlindSpotPreviewSurface(index, surface) },
            detachPreview = { index -> detachBlindSpotPreviewSurface(index) },
            restoreMainPreview = { index -> restoreMainPreviewSurface(index) },
            hideFisheyePreview = { fisheyePreviewController.hide() },
            hideUi = { hideUiForAvoidance() },
            restoreUi = { restoreUiFromBlindSpot() },
            renderedFrames = { index -> engine.previewRenderedFrames(index) },
            showToast = { message -> showServiceToast(message) },
        )
        avoidanceController = V2AvoidanceController(
            context = this,
            handler = mainHandler,
            foregroundAppMonitor = V2ForegroundAppMonitor(this),
            isDisplayPowerOn = { isDisplayPowerOn() },
            isRecording = { engine.isRecording() },
            isUiVisible = { uiVisible },
            onHideBlindSpot = { blindSpotController.cancelAndHideForAvoidance() },
            onHideFisheye = { fisheyePreviewController.hide() },
            onCancelAutoRecording = { autoRecordingController.cancelPending() },
            onHideUi = { hideUiForAvoidance() },
            onStopRecording = {
                engine.stopRecording()
                updatePlaybackCacheRecordingState()
                uiStatusListener?.invoke(engine.statusText())
            },
            onRestoreRecording = {
                engine.startRecording()
                updatePlaybackCacheRecordingState()
                uiStatusListener?.invoke(engine.statusText())
            },
            onRestoreUi = { restoreUiFromAvoidance() },
            onScheduleAutoRecording = { V2AppLog.i("V2CameraService", "auto recording restore skipped: cold start only") },
            showToast = { showServiceToast(it) }
        )
        notificationHelper.startForeground("camera ready")
        V2AppLog.i("V2CameraService", "foreground notification started")
        displayPowerController.register()
        engine.setCameraAccessAllowed(isDisplayPowerOn())
        if (isDisplayPowerOn()) engine.startCameras()
        wakeLockHolder.acquire()
        customKeyController.start()
        blindSpotController.startObserver()
        avoidanceController.start()
        cameraWatchdog.start()
        V2KeepAliveScheduler.schedule(this)
        V2KeepAliveReceiver.registerDynamic(this)
        updatePlaybackCacheRecordingState()
        V2PlaybackCacheMaintainer.scheduleRefresh(this)
        autoRecordingController.scheduleIfEnabled()
        updateStatusBarPluginState()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2BroadcastLogger.logServiceStart("V2CameraService", intent, flags, startId)
        V2KeepAliveStatus.recordTrigger(this, "service", intent?.action ?: "start_command")
        val action = intent?.action
        ensureKeepAliveChain("start_command")
        when {
            action == ACTION_AUTO_START_RECORDING -> autoRecordingController.scheduleIfEnabled()
            action == ACTION_REFRESH_CUSTOM_KEY -> customKeyController.restart()
            action == ACTION_REFRESH_BLIND_SPOT -> blindSpotController.restartObserver()
            action == ACTION_REFRESH_FISHEYE -> engine.applyFisheyeSettings()
            action == ACTION_REFRESH_WAKE_LOCK -> refreshWakeLock()
            action == ACTION_SHOW_FISHEYE_PREVIEW -> fisheyePreviewController.show(intent.getIntExtra(EXTRA_CAMERA_INDEX, 0))
            action == ACTION_HIDE_FISHEYE_PREVIEW -> fisheyePreviewController.hide()
            action == ACTION_SHOW_BLIND_SPOT_PREVIEW -> blindSpotController.showPreview(intent.getStringExtra(EXTRA_SIDE) ?: "left")
            action == ACTION_HIDE_BLIND_SPOT_PREVIEW -> blindSpotController.hide()
            action == ACTION_TOGGLE_RECORDING_FROM_PLUGIN -> toggleRecordingFromPlugin()
            action == ACTION_START_EMERGENCY_FROM_PLUGIN -> startEmergencyRecordingFromPlugin()
            V2DisplayPowerActions.isDisplayOff(action) -> handleDisplayOff(action)
            V2DisplayPowerActions.isDisplayOn(action) -> handleDisplayOn(action)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        V2AppLog.i("V2CameraService", "onDestroy recording=${engine.isRecording()}")
        V2KeepAliveStatus.recordServiceDestroyed(this)
        val shouldRestart = !manualShutdown && V2KeepAliveSettings.isKeepAliveEnabled(this) && V2StartupSettings.isAutoStartOnBoot(this)
        mainHandler.removeCallbacksAndMessages(null)
        displayPowerController.unregister()
        V2KeepAliveReceiver.unregisterDynamic(this)
        blindSpotController.stopObserver()
        blindSpotController.hide()
        fisheyePreviewController.hide()
        customKeyController.stop()
        releaseWakeLock()
        engine.release()
        if (shouldRestart) scheduleServiceRestart("on_destroy")
        V2StatusBarStateStore.update(this, false, false, false, "")
        V2AppLog.saveToPersistentLog(this)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (manualShutdown) {
            V2AppLog.i("V2CameraService", "task removed ignored: manual shutdown")
            super.onTaskRemoved(rootIntent)
            return
        }
        V2AppLog.w("V2CameraService", "task removed, requesting service restart")
        scheduleServiceRestart("task_removed")
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureKeepAliveChain(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (isDisplayPowerOn() && now - lastKeepAliveChainMs < KEEP_ALIVE_CHAIN_INTERVAL_MS) {
            refreshWakeLock()
            return
        }
        lastKeepAliveChainMs = now
        releaseCamerasIfSystemAlreadyNonInteractive("keep_alive:$reason")
        V2KeepAliveReceiver.registerTimeTick(this)
        V2KeepAliveScheduler.schedule(this)
        V2KeepAliveStatus.recordTrigger(this, "chain", reason)
        refreshWakeLock()
    }

    private fun scheduleServiceRestart(reason: String) {
        V2KeepAliveStatus.recordTrigger(this, "service_restart", reason)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                V2CameraServiceCommands.start(applicationContext)
                V2AppLog.w("V2CameraService", "delayed restart requested reason=$reason")
            }.onFailure { V2AppLog.e("V2CameraService", "delayed restart failed reason=$reason", it) }
        }, SERVICE_RESTART_DELAY_MS)
        V2KeepAliveReceiver.sendKeepAliveCheck(applicationContext)
    }

    fun toggleRecording(): Boolean {
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "toggleRecording skipped: display off")
            return false
        }
        val result = engine.toggleRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        V2AppLog.i("V2CameraService", "toggleRecording result=$result")
        return result
    }
    fun isRecording(): Boolean = engine.isRecording()
    fun statusText(): String = engine.statusText()
    fun isPreviewPausedByAvoidance(): Boolean = false
    fun ensureReadyAfterPermissions() {
        V2AppLog.i("V2CameraService", "ensureReadyAfterPermissions displayPowerOn=${isDisplayPowerOn()} recording=${engine.isRecording()}")
        if (!isDisplayPowerOn()) return
        engine.setCameraAccessAllowed(true)
        engine.startCameras()
        previewSurfaces.forEachIndexed { index, surface ->
            previewLeaseManager.restoreMain(index, surface)
        }
        cameraWatchdog.reset("permission_ready")
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        uiStatusListener?.invoke(engine.statusText())
    }
    fun previewInputSizeLabel(index: Int): String = engine.previewInputSizeLabel(index)
    fun previewInputSize(index: Int): android.util.Size? = engine.previewInputSize(index)
    fun attachPreviewSurface(index: Int, surface: Surface) {
        previewSurfaces[index] = surface
        previewLeaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.MAIN)
    }
    fun detachPreviewSurface(index: Int) {
        previewSurfaces[index] = null
        previewLeaseManager.detach(index, V2PreviewLeaseManager.Owner.MAIN)
    }
    internal fun attachFisheyePreviewSurface(index: Int, surface: Surface) {
        previewLeaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.FISHEYE)
    }
    internal fun detachFisheyePreviewSurface(index: Int) {
        previewLeaseManager.detach(index, V2PreviewLeaseManager.Owner.FISHEYE)
    }
    internal fun canShowFisheyePreview(index: Int): Boolean {
        if (blindSpotController.activeCameraIndex >= 0 || previewLeaseManager.isOwnedBy(index, V2PreviewLeaseManager.Owner.BLIND_SPOT)) {
            V2AppLog.i("V2CameraService", "fisheye preview denied: blind spot active index=$index blindSpotIndex=${blindSpotController.activeCameraIndex}")
            return false
        }
        return true
    }
    private fun attachBlindSpotPreviewSurface(index: Int, surface: Surface) {
        previewLeaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.BLIND_SPOT)
    }
    private fun detachBlindSpotPreviewSurface(index: Int) {
        previewLeaseManager.detach(index, V2PreviewLeaseManager.Owner.BLIND_SPOT)
    }

    private fun restoreMainPreviewSurface(index: Int) {
        previewLeaseManager.restoreMain(index, previewSurfaces.getOrNull(index))
    }
    fun startRecording() {
        V2AppLog.i("V2CameraService", "manual startRecording displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        if (avoidanceController.isActive) {
            V2AppLog.w("V2CameraService", "manual startRecording skipped: avoidance active target=${avoidanceController.activeTarget}")
            return
        }
        if (isDisplayPowerOn()) {
            engine.startRecording()
            updatePlaybackCacheRecordingState()
            updateStatusBarPluginState()
        } else {
            V2AppLog.w("V2CameraService", "manual startRecording skipped: display off")
        }
    }
    fun stopRecording() { V2AppLog.i("V2CameraService", "manual stopRecording"); engine.stopRecording(); updatePlaybackCacheRecordingState(); updateStatusBarPluginState() }

    fun startEmergencyRecording(durationMs: Long = EMERGENCY_RECORDING_DURATION_MS, onStateChanged: ((Boolean) -> Unit)? = null): Boolean {
        V2AppLog.i("V2CameraService", "emergency start requested durationMs=$durationMs active=$emergencyRecordingActive recording=${engine.isRecording()}")
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "emergency skipped: display off")
            return false
        }
        if (avoidanceController.isActive) {
            V2AppLog.w("V2CameraService", "emergency skipped: avoidance active target=${avoidanceController.activeTarget}")
            return false
        }
        if (emergencyRecordingActive) return false

        emergencyRecordingActive = true
        emergencyRecordingEndsAtMs = android.os.SystemClock.elapsedRealtime() + durationMs
        resumeNormalRecordingAfterEmergency = engine.isRecording()
        emergencyRecordingStopRunnable?.let(mainHandler::removeCallbacks)
        emergencyRecordingStopRunnable = null
        onStateChanged?.invoke(true)
        notifyEmergencyRecordingState(true)
        updateStatusBarPluginState()

        if (resumeNormalRecordingAfterEmergency) {
            if (!engine.requestEmergencyClip(durationMs)) {
                emergencyRecordingActive = false
                emergencyRecordingEndsAtMs = 0L
                resumeNormalRecordingAfterEmergency = false
                onStateChanged?.invoke(false)
                notifyEmergencyRecordingState(false)
                updateStatusBarPluginState()
                return false
            }
        } else {
            engine.startEventRecording(durationMs)
        }
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        uiStatusListener?.invoke(engine.statusText())

        val stopRunnable = Runnable { finishEmergencyRecording(onStateChanged) }
        emergencyRecordingStopRunnable = stopRunnable
        mainHandler.postDelayed(stopRunnable, durationMs)
        return true
    }

    private fun finishEmergencyRecording(onStateChanged: ((Boolean) -> Unit)?) {
        if (!emergencyRecordingActive) return
        V2AppLog.i("V2CameraService", "emergency finish resumeNormal=$resumeNormalRecordingAfterEmergency recording=${engine.isRecording()}")
        emergencyRecordingActive = false
        emergencyRecordingStopRunnable = null
        emergencyRecordingEndsAtMs = 0L
        if (!resumeNormalRecordingAfterEmergency) engine.stopRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        uiStatusListener?.invoke(engine.statusText())
        onStateChanged?.invoke(false)
        notifyEmergencyRecordingState(false)
        resumeNormalRecordingAfterEmergency = false
    }

    private fun startAutoRecordingIfAllowed() {
        if (avoidanceController.isActive) {
            V2AppLog.i("V2CameraService", "auto recording skipped: avoidance active target=${avoidanceController.activeTarget}")
            return
        }
        engine.startRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
    }
    fun shutdownFromUi() {
        V2AppLog.w("V2CameraService", "manual shutdown from UI")
        manualShutdown = true
        mainHandler.removeCallbacksAndMessages(null)
        engine.stopRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        uiStatusListener = listener
        listener?.invoke(engine.statusText())
    }

    fun setUiEmergencyRecordingListener(listener: ((Boolean, Long) -> Unit)?) {
        uiEmergencyRecordingListener = listener
        listener?.invoke(emergencyRecordingActive, emergencyRecordingEndsAtMs)
    }

    private fun notifyEmergencyRecordingState(active: Boolean) {
        uiEmergencyRecordingListener?.invoke(active, emergencyRecordingEndsAtMs)
    }
    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        uiVisible = visible
        uiHideListener = if (visible) hideListener else null
        V2AppLog.i("V2CameraService", "uiVisible=$uiVisible")
        updatePreviewRenderingEnabled()
    }

    private fun updatePreviewRenderingEnabled() {
        if (!::engine.isInitialized) return
        engine.setPreviewRenderingEnabled(uiVisible || previewLeaseManager.hasOverlayOwner())
    }

    private fun handleDisplayOff(action: String?) {
        displayPowerController.markOff(action)
        resumeRecordingAfterDisplayOn = engine.isRecording()
        V2AppLog.i("V2CameraService", "display off/pre-STR action=$action: stop recording, detach preview, release cameras resumeRecording=$resumeRecordingAfterDisplayOn")
        cameraWatchdog.reset("display_off")
        autoRecordingController.cancelPending()
        avoidanceController.clear("display off")
        blindSpotController.hide()
        fisheyePreviewController.hide()
        pauseCameraForDisplayOff()
        uiStatusListener?.invoke(engine.statusText())
        V2AppLog.saveToPersistentLog(this)
    }

    private fun releaseCamerasIfSystemAlreadyNonInteractive(reason: String) {
        if (!isDisplayPowerOn()) {
            engine.stopRecordingAndReleaseCameras("$reason:display_power_off")
            updatePlaybackCacheRecordingState()
            return
        }
        displayPowerController.queryCurrentState(reason)
    }

    private fun handleDisplayOn(action: String?) {
        if (isDisplayPowerOn()) {
            displayPowerController.markOnIfAlreadyOn(action)
            V2AppLog.i("V2CameraService", "display on ignored: already on action=$action")
            return
        }
        displayPowerController.markOn(action)
        V2AppLog.i("V2CameraService", "display on action=$action: allow cameras and reconnect previews")
        resumeCameraForDisplayOn()
        previewSurfaces.forEachIndexed { index, surface ->
            previewLeaseManager.restoreMain(index, surface)
        }
        restoreRecordingAfterDisplayOnIfNeeded()
        autoRecordingController.cancelPending()
        cameraWatchdog.reset("display_on")
        cameraWatchdog.start()
        uiStatusListener?.invoke(engine.statusText())
    }

    private fun restoreRecordingAfterDisplayOnIfNeeded() {
        if (!resumeRecordingAfterDisplayOn) return
        resumeRecordingAfterDisplayOn = false
        mainHandler.postDelayed({
            if (!isDisplayPowerOn()) {
                V2AppLog.i("V2CameraService", "display-on recording restore skipped: display off")
                return@postDelayed
            }
            if (avoidanceController.isActive) {
                V2AppLog.i("V2CameraService", "display-on recording restore skipped: avoidance active target=${avoidanceController.activeTarget}")
                return@postDelayed
            }
            if (engine.isRecording()) {
                V2AppLog.i("V2CameraService", "display-on recording restore skipped: already recording")
                return@postDelayed
            }
            V2AppLog.i("V2CameraService", "display-on recording restore start")
            engine.startRecording()
            updatePlaybackCacheRecordingState()
            updateStatusBarPluginState()
            uiStatusListener?.invoke(engine.statusText())
        }, 500L)
    }

    private fun showUiFromCustomKey() {
        val intent = Intent(this, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { V2AppLog.e("V2CameraService", "custom key show UI failed", it) }
    }

    private fun hideUiForAvoidance() {
        if (uiVisible && uiHideListener != null) {
            uiHideListener?.invoke()
            return
        }
        V2AppLog.w("V2CameraService", "avoidance hide UI skipped: ui callback unavailable")
    }

    private fun restoreUiFromAvoidance() {
        val intent = Intent(this, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { V2AppLog.e("V2CameraService", "avoidance restore UI failed", it) }
    }

    private fun restoreUiFromBlindSpot() {
        val intent = Intent(this, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { V2AppLog.e("V2CameraService", "blind spot restore UI failed", it) }
    }

    private fun restartCamerasFromWatchdog(reason: String) {
        if (!isDisplayPowerOn()) return
        V2AppLog.e("V2CameraService", "watchdog restarting cameras reason=$reason")
        val wasRecording = engine.isRecording()
        runCatching {
            if (wasRecording) {
                engine.stopRecording()
                updatePlaybackCacheRecordingState()
                updateStatusBarPluginState()
            }
            engine.stopCameras()
            engine.startCameras()
            previewSurfaces.forEachIndexed { index, surface ->
                previewLeaseManager.restoreMain(index, surface)
            }
            if (wasRecording && isDisplayPowerOn()) {
                mainHandler.postDelayed({
                    if (isDisplayPowerOn() && !avoidanceController.isActive && !engine.isRecording()) {
                        V2AppLog.w("V2CameraService", "watchdog restarting recording")
                        engine.startRecording()
                        updatePlaybackCacheRecordingState()
                        updateStatusBarPluginState()
                    }
                }, WATCHDOG_RECORDING_RESTART_DELAY_MS)
            }
            uiStatusListener?.invoke(engine.statusText())
        }.onFailure { V2AppLog.e("V2CameraService", "watchdog camera restart failed", it) }
    }

    private fun shouldExpectPreviewRendering(recording: Boolean): Boolean {
        return recording || uiVisible || previewLeaseManager.hasOverlayOwner()
    }

    private fun showServiceToast(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastToastText && now - lastToastMs < 5_000L) return
        lastToastText = message
        lastToastMs = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

    private fun pauseCameraForDisplayOff() {
        engine.stopRecordingAndReleaseCameras("display_off")
        updatePlaybackCacheRecordingState()
    }

    private fun resumeCameraForDisplayOn() {
        engine.setCameraAccessAllowed(true)
    }

    private fun refreshWakeLock() = wakeLockHolder.acquire()

    private fun releaseWakeLock() = wakeLockHolder.release()

    override fun onStatusChanged(status: String) {
        val recording = parseRecordingState(status)
        updateUiStatusIfChanged(status)
        recording?.let { V2PlaybackCacheMaintainer.setRecordingActive(it) }
        updateStatusBarPluginState(status)
        if (shouldUpdateNotification(status)) {
            updateStatusBarNotification(status, recording)
        }
    }

    private fun updateStatusBarNotification(status: String, recording: Boolean? = parseRecordingState(status)) {
        notificationHelper.update(statusBarNotificationText(status))
        lastNotificationText = status
        lastNotificationMs = System.currentTimeMillis()
        lastNotificationRecording = recording
        lastNotificationEmergency = emergencyRecordingActive
    }

    private fun statusBarNotificationText(status: String): String {
        val emergencyLine = if (emergencyRecordingActive) "emg=ON" else "emg=OFF"
        val endLine = if (emergencyRecordingActive) "emgEnd=${emergencyRecordingEndsAtWallClockMs()}" else "emgEnd=0"
        return if (status.contains("emg=")) status else "$status\n$emergencyLine\n$endLine"
    }

    private fun emergencyRecordingEndsAtWallClockMs(): Long {
        if (!emergencyRecordingActive || emergencyRecordingEndsAtMs <= 0L) return 0L
        val remainingMs = emergencyRecordingEndsAtMs - android.os.SystemClock.elapsedRealtime()
        return if (remainingMs > 0L) System.currentTimeMillis() + remainingMs else 0L
    }

    private fun updateUiStatusIfChanged(status: String) {
        if (status == lastUiStatusText) return
        lastUiStatusText = status
        uiStatusListener?.invoke(status)
    }

    private fun shouldUpdateNotification(status: String): Boolean {
        val now = System.currentTimeMillis()
        val recording = parseRecordingState(status)
        val recordingChanged = recording != null && lastNotificationRecording != recording
        val emergencyChanged = lastNotificationEmergency != emergencyRecordingActive
        val textChanged = lastNotificationText != status
        return recordingChanged || emergencyChanged || lastNotificationText == null || textChanged && now - lastNotificationMs >= 15_000L
    }

    private fun parseRecordingState(status: String): Boolean? {
        val prefix = status.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            prefix.startsWith("rec=ON") -> true
            prefix.startsWith("rec=OFF") -> false
            else -> null
        }
    }

    private fun updatePlaybackCacheRecordingState() {
        if (!::engine.isInitialized) return
        V2PlaybackCacheMaintainer.setRecordingActive(engine.isRecording())
    }

    private fun toggleRecordingFromPlugin() {
        V2AppLog.i("V2CameraService", "plugin toggle recording")
        toggleRecording()
    }

    private fun startEmergencyRecordingFromPlugin() {
        V2AppLog.i("V2CameraService", "plugin emergency recording")
        if (emergencyRecordingActive) {
            showServiceToast("紧急录制已在进行")
            return
        }
        val started = startEmergencyRecording()
        if (!started) showServiceToast("紧急录制启动失败")
    }

    private fun updateStatusBarPluginState(status: String = if (::engine.isInitialized) engine.statusText() else "") {
        if (!::engine.isInitialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        val recording = engine.isRecording()
        val stateChanged = recording != lastStatusBarRecording || emergencyRecordingActive != lastStatusBarEmergency
        val textRefreshDue = status != lastStatusBarStatus && now - lastStatusBarUpdateMs >= STATUS_BAR_STATE_INTERVAL_MS
        if (!stateChanged && !textRefreshDue && lastStatusBarStatus != null) return
        lastStatusBarRecording = recording
        lastStatusBarEmergency = emergencyRecordingActive
        lastStatusBarStatus = status
        lastStatusBarUpdateMs = now
        V2StatusBarStateStore.update(this, true, recording, emergencyRecordingActive, status, emergencyRecordingEndsAtWallClockMs())
        if (stateChanged) updateStatusBarNotification(status, recording)
    }
}
