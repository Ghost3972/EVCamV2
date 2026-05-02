package com.kooo.evcam.v2.service

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

internal class V2RecordingOrchestrator(
    private val handler: Handler,
    private val engine: V2CameraEngine,
    private val isDisplayPowerOn: () -> Boolean,
    private val isSystemInteractive: () -> Boolean,
    private val isAvoidanceActive: () -> Boolean,
    private val avoidanceTarget: () -> String?,
    private val updatePlaybackCacheRecordingState: () -> Unit,
    private val updateStatusBarPluginState: () -> Unit,
    private val notifyUiStatus: (String) -> Unit,
    private val notifyEmergencyRecordingState: (Boolean, Long) -> Unit,
    private val showToast: (String) -> Unit,
) {
    var emergencyRecordingActive: Boolean = false
        private set
    var emergencyRecordingEndsAtMs: Long = 0L
        private set

    private var resumeNormalRecordingAfterEmergency = false
    private var emergencyRecordingStopRunnable: Runnable? = null

    fun toggleRecording(): Boolean {
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "toggleRecording skipped: display off")
            return false
        }
        val result = engine.toggleRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        V2AppLog.d(TAG, "toggleRecording result=$result")
        return result
    }

    fun startRecording() {
        V2AppLog.i(TAG, "manual startRecording displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        if (isAvoidanceActive()) {
            V2AppLog.w(TAG, "manual startRecording skipped: avoidance active target=${avoidanceTarget()}")
            return
        }
        if (isDisplayPowerOn()) {
            engine.startRecording()
            updatePlaybackCacheRecordingState()
            updateStatusBarPluginState()
        } else {
            V2AppLog.w(TAG, "manual startRecording skipped: display off")
        }
    }

    fun stopRecording() {
        V2AppLog.i(TAG, "manual stopRecording")
        engine.stopRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
    }

    fun startAutoRecordingIfAllowed() {
        if (isAvoidanceActive()) {
            V2AppLog.i(TAG, "auto recording skipped: avoidance active target=${avoidanceTarget()}")
            return
        }
        engine.startRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
    }

    fun startEmergencyRecording(
        durationMs: Long = V2CameraForegroundService.EMERGENCY_RECORDING_DURATION_MS,
        onStateChanged: ((Boolean) -> Unit)? = null,
    ): Boolean {
        V2AppLog.i(TAG, "emergency start requested durationMs=$durationMs active=$emergencyRecordingActive recording=${engine.isRecording()}")
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "emergency skipped: display off")
            return false
        }
        if (isAvoidanceActive()) {
            V2AppLog.w(TAG, "emergency skipped: avoidance active target=${avoidanceTarget()}")
            return false
        }
        if (emergencyRecordingActive) return false

        emergencyRecordingActive = true
        emergencyRecordingEndsAtMs = android.os.SystemClock.elapsedRealtime() + durationMs
        resumeNormalRecordingAfterEmergency = engine.isRecording()
        emergencyRecordingStopRunnable?.let(handler::removeCallbacks)
        emergencyRecordingStopRunnable = null
        onStateChanged?.invoke(true)
        notifyEmergencyRecordingState(true, emergencyRecordingEndsAtMs)
        updateStatusBarPluginState()

        if (resumeNormalRecordingAfterEmergency) {
            if (!engine.requestEmergencyClip(durationMs)) {
                emergencyRecordingActive = false
                emergencyRecordingEndsAtMs = 0L
                resumeNormalRecordingAfterEmergency = false
                onStateChanged?.invoke(false)
                notifyEmergencyRecordingState(false, emergencyRecordingEndsAtMs)
                updateStatusBarPluginState()
                return false
            }
        } else {
            engine.startEventRecording(durationMs)
        }
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        notifyUiStatus(engine.statusText())

        val stopRunnable = Runnable { finishEmergencyRecording(onStateChanged) }
        emergencyRecordingStopRunnable = stopRunnable
        handler.postDelayed(stopRunnable, durationMs)
        return true
    }

    fun toggleRecordingFromPlugin() {
        V2AppLog.i(TAG, "plugin toggle recording")
        toggleRecording()
    }

    fun startEmergencyRecordingFromPlugin() {
        V2AppLog.i(TAG, "plugin emergency recording")
        if (emergencyRecordingActive) {
            showToast("紧急录制已在进行")
            return
        }
        val started = startEmergencyRecording()
        if (!started) showToast("紧急录制启动失败")
    }

    fun emergencyRecordingEndsAtWallClockMs(): Long {
        if (!emergencyRecordingActive || emergencyRecordingEndsAtMs <= 0L) return 0L
        val remainingMs = emergencyRecordingEndsAtMs - android.os.SystemClock.elapsedRealtime()
        return if (remainingMs > 0L) System.currentTimeMillis() + remainingMs else 0L
    }

    private fun finishEmergencyRecording(onStateChanged: ((Boolean) -> Unit)?) {
        if (!emergencyRecordingActive) return
        V2AppLog.i(TAG, "emergency finish resumeNormal=$resumeNormalRecordingAfterEmergency recording=${engine.isRecording()}")
        emergencyRecordingActive = false
        emergencyRecordingStopRunnable = null
        emergencyRecordingEndsAtMs = 0L
        if (!resumeNormalRecordingAfterEmergency) engine.stopRecording()
        updatePlaybackCacheRecordingState()
        updateStatusBarPluginState()
        notifyUiStatus(engine.statusText())
        onStateChanged?.invoke(false)
        notifyEmergencyRecordingState(false, emergencyRecordingEndsAtMs)
        resumeNormalRecordingAfterEmergency = false
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
