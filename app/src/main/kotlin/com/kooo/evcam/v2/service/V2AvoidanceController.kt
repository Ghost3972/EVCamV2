package com.kooo.evcam.v2.service

import android.content.Context
import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2SettingsRepository
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

class V2AvoidanceController(
    private val context: Context,
    private val handler: Handler,
    private val foregroundAppMonitor: V2ForegroundAppMonitor,
    private val isDisplayPowerOn: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val isUiVisible: () -> Boolean,
    private val onHideBlindSpot: () -> Unit,
    private val onHideFisheye: () -> Unit,
    private val onCancelAutoRecording: () -> Unit,
    private val onHideUi: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onRestoreRecording: () -> Unit,
    private val onRestoreUi: () -> Unit,
    private val onScheduleAutoRecording: () -> Unit,
    private val showToast: (String) -> Unit,
) {
    @Volatile private var config: V2SettingsSnapshot.Avoidance = V2SettingsRepository.avoidanceConfig(context)
    private var snapshot: Snapshot? = null
    private var target: String? = null
    private var lastDecisionLogMs = 0L

    val isActive: Boolean get() = snapshot != null
    val activeTarget: String? get() = target

    private val tick = object : Runnable {
        override fun run() {
            runCatching { checkTarget() }
                .onFailure { V2AppLog.e(TAG, "avoidance monitor tick failed", it) }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
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
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    fun clear(reason: String) {
        if (snapshot != null) V2AppLog.i(TAG, "$reason clears active avoidance target=$target")
        snapshot = null
        target = null
    }

    fun currentTarget(): String? {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val targets = config.targets
        val current = if (behaviorMask == 0 || !displayOn) null else foregroundAppMonitor.findForegroundTarget(targets)
        logDecision("currentTarget", behaviorMask, displayOn, targets, current, current != null)
        return current
    }

    fun shouldAvoidBlindSpotWindow(): Boolean {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val hideBlindSpotEnabled = config.hideBlindSpot
        val targets = config.targets
        val current = if (hideBlindSpotEnabled && displayOn && !isActive) foregroundAppMonitor.findForegroundTarget(targets) else target
        val avoid = hideBlindSpotEnabled && displayOn && (isActive || current != null)
        logDecision("shouldAvoidBlindSpotWindow", behaviorMask, displayOn, targets, current, avoid)
        return avoid
    }

    private fun checkTarget() {
        val behaviorMask = config.behaviorMask
        val displayOn = isDisplayPowerOn()
        val targets = config.targets
        val currentTarget = if (behaviorMask == 0 || !displayOn) null else foregroundAppMonitor.findForegroundTarget(targets)
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
        val currentSnapshot = Snapshot(
            behaviorMask = behaviorMask,
            wasRecording = isRecording(),
            wasUiVisible = isUiVisible()
        )
        snapshot = currentSnapshot
        this.target = target
        V2AppLog.i(TAG, "enter avoidance target=$target behavior=${config.behaviorLabels()} wasRecording=${currentSnapshot.wasRecording} wasUiVisible=${currentSnapshot.wasUiVisible}")

        if (config.hideBlindSpot) {
            V2AppLog.i(TAG, "avoidance hide blind spot overlay")
            onHideBlindSpot()
        }
        onHideFisheye()
        showToast("避让中")
        onCancelAutoRecording()

        if (config.exitForeground) {
            V2AppLog.i(TAG, "avoidance hide UI")
            onHideUi()
        }
        if (config.stopRecording && isRecording()) {
            V2AppLog.i(TAG, "avoidance stop recording")
            onStopRecording()
        }
    }

    private fun exit() {
        val oldSnapshot = snapshot ?: return
        val oldTarget = target
        snapshot = null
        target = null
        V2AppLog.i(TAG, "exit avoidance target=$oldTarget restoreRecording=${oldSnapshot.wasRecording} restoreUi=${oldSnapshot.wasUiVisible}")
        if (oldSnapshot.wasRecording && isDisplayPowerOn() && !isRecording()) {
            onRestoreRecording()
        }
        showToast("避让结束")
        if (oldSnapshot.wasUiVisible) {
            onRestoreUi()
        } else {
            onScheduleAutoRecording()
        }
    }

    private data class Snapshot(
        val behaviorMask: Int,
        val wasRecording: Boolean,
        val wasUiVisible: Boolean,
    )

    private fun logDecision(
        source: String,
        behaviorMask: Int,
        displayOn: Boolean,
        targets: List<String>,
        currentTarget: String?,
        result: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDecisionLogMs < DECISION_LOG_INTERVAL_MS) return
        lastDecisionLogMs = now
        V2AppLog.i(
            TAG,
            "avoidance decision source=$source result=$result active=$isActive activeTarget=$target " +
                "behaviorMask=$behaviorMask behavior=${config.behaviorLabels()} " +
                "displayOn=$displayOn targets=${targets.joinToString()} currentTarget=$currentTarget"
        )
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val CHECK_INTERVAL_MS = 2_000L
        private const val DECISION_LOG_INTERVAL_MS = 10_000L
    }
}
