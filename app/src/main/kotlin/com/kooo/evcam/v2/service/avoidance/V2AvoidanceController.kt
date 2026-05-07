package com.kooo.evcam.v2.service.avoidance

import android.content.Context
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2AvoidanceController(
    private val context: Context,
    private val handler: Handler,
    private val targetDetector: V2ForegroundTargetDetector,
    private val isDisplayPowerOn: () -> Boolean,
    isRecording: () -> Boolean,
    isUiVisible: () -> Boolean,
    onHideBlindSpot: () -> Unit,
    onHideFisheye: () -> Unit,
    onCancelAutoRecording: () -> Unit,
    onHideUi: () -> Unit,
    onStopRecording: () -> Unit,
    onRestoreRecording: () -> Unit,
    onRestoreUi: () -> Unit,
    onScheduleAutoRecording: () -> Unit,
    showToast: (String) -> Unit,
) {
    @Volatile private var config: V2SettingsSnapshot.Avoidance = V2SettingsRepository.avoidanceConfig(context)
    private var snapshot: V2AvoidanceSnapshot? = null
    private var target: String? = null
    @Volatile private var lastForegroundTarget: String? = null
    private var restoreGeneration = 0
    private val decisionLogger = V2AvoidanceDecisionLogger()
    private val sessionActions = V2AvoidanceSessionActions(
        isDisplayPowerOn = isDisplayPowerOn,
        isRecording = isRecording,
        isUiVisible = isUiVisible,
        onHideBlindSpot = onHideBlindSpot,
        onHideFisheye = onHideFisheye,
        onCancelAutoRecording = onCancelAutoRecording,
        onHideUi = onHideUi,
        onStopRecording = onStopRecording,
        onRestoreUi = onRestoreUi,
        onScheduleAutoRecording = onScheduleAutoRecording,
        showToast = showToast,
    )
    private val recordingRestorer = V2AvoidanceRecordingRestorer(
        handler = handler,
        isDisplayPowerOn = isDisplayPowerOn,
        isRecording = isRecording,
        isAvoidanceActive = { snapshot != null },
        currentGeneration = { restoreGeneration },
        onRestoreRecording = onRestoreRecording,
        onScheduleAutoRecording = onScheduleAutoRecording,
    )

    val isActive: Boolean get() = snapshot != null
    val activeTarget: String? get() = target

    private val tick = object : Runnable {
        override fun run() {
            runCatching { checkTarget() }
                .onFailure { V2AppLog.e(TAG, "avoidance monitor tick failed", it) }
            handler.postDelayed(this, checkIntervalMs())
        }
    }

    fun start() {
        updateConfig(V2SettingsRepository.avoidanceConfig(context))
        handler.removeCallbacks(tick)
        handler.post(tick)
        V2AppLog.i(TAG, "avoidance monitor started targets=${config.targets.joinToString()} behavior=${config.behaviorLabels()}")
    }

    fun updateConfig(next: V2SettingsSnapshot.Avoidance) {
        val old = config
        config = next
        if (!next.enabled && snapshot != null) exit()
        if (old != next) {
            V2AppLog.i(TAG, "avoidance config updated targets=${next.targets.joinToString()} behavior=${next.behaviorLabels()}")
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        targetDetector.cancel()
    }

    fun clear(reason: String) {
        if (snapshot != null) V2AppLog.i(TAG, "$reason clears active avoidance target=$target")
        restoreGeneration++
        snapshot = null
        target = null
        lastForegroundTarget = null
        targetDetector.cancel()
    }

    fun currentTarget(): String? {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val targets = config.targets
        val current = if (behaviorMask == 0 || !displayOn) null else lastForegroundTarget
        logDecision("currentTarget", behaviorMask, displayOn, targets, current, current != null)
        return current
    }

    fun shouldAvoidBlindSpotWindow(): Boolean {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val hideBlindSpotEnabled = config.hideBlindSpot
        val targets = config.targets
        val current = if (hideBlindSpotEnabled && displayOn && !isActive) lastForegroundTarget else target
        val avoid = hideBlindSpotEnabled && displayOn && (isActive || current != null)
        logDecision("shouldAvoidBlindSpotWindow", behaviorMask, displayOn, targets, current, avoid)
        return avoid
    }

    private fun checkTarget() {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val targets = config.targets
        if (behaviorMask == 0 || !displayOn) {
            targetDetector.cancel()
            handleTargetResult(null)
            return
        }
        targetDetector.detect(targets) { currentTarget ->
            handleTargetResult(currentTarget)
        }
    }

    private fun handleTargetResult(detectedTarget: String?) {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val targets = config.targets
        val currentTarget = if (behaviorMask == 0 || !displayOn) null else detectedTarget?.takeIf { it in targets }
        lastForegroundTarget = currentTarget
        logDecision("checkTarget", behaviorMask, displayOn, targets, currentTarget, currentTarget != null)
        when {
            currentTarget != null && snapshot == null -> enter(currentTarget, behaviorMask)
            currentTarget == null && snapshot != null -> exit()
            currentTarget != null && currentTarget != target -> {
                V2AppLog.i(TAG, "avoidance target changed $target -> $currentTarget")
                target = currentTarget
            }
        }
    }

    private fun enter(target: String, behaviorMask: Int) {
        val currentSnapshot = sessionActions.createSnapshot(behaviorMask)
        snapshot = currentSnapshot
        this.target = target
        restoreGeneration++
        sessionActions.enter(target, config, currentSnapshot)
    }

    private fun exit() {
        val oldSnapshot = snapshot ?: return
        val oldTarget = target
        snapshot = null
        target = null
        val generation = ++restoreGeneration
        sessionActions.exit(oldSnapshot, oldTarget, generation, recordingRestorer)
    }

    private fun checkIntervalMs(): Long = if (snapshot != null) ACTIVE_CHECK_INTERVAL_MS else IDLE_CHECK_INTERVAL_MS

    private fun logDecision(
        source: String,
        behaviorMask: Int,
        displayOn: Boolean,
        targets: List<String>,
        currentTarget: String?,
        result: Boolean,
    ) {
        decisionLogger.log(
            source = source,
            behaviorMask = behaviorMask,
            behaviorLabels = config.behaviorLabels(),
            displayOn = displayOn,
            targets = targets,
            currentTarget = currentTarget,
            result = result,
            active = isActive,
            activeTarget = target,
        )
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val ACTIVE_CHECK_INTERVAL_MS = 300L
        private const val IDLE_CHECK_INTERVAL_MS = 500L
    }
}
