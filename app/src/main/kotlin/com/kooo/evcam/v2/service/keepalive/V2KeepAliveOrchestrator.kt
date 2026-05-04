package com.kooo.evcam.v2.service.keepalive

import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.service.commands.V2CameraServiceCommands
import com.kooo.evcam.v2.settings.V2SettingsSnapshot

internal class V2KeepAliveOrchestrator(
    private val service: V2CameraForegroundService,
    private val isDisplayPowerOn: () -> Boolean,
    private val startupPolicy: () -> V2SettingsSnapshot.Startup,
    private val keepAlivePolicy: () -> V2SettingsSnapshot.KeepAlive,
    private val releaseCamerasIfSystemAlreadyNonInteractive: (reason: String) -> Unit,
) {
    private val wakeLockHolder = V2WakeLockHolder(service)
    private var manualShutdown = false
    private var lastKeepAliveChainMs = 0L

    fun recordCreated() {
        V2KeepAliveStatus.recordServiceCreated(service)
        V2KeepAliveStatus.recordTrigger(service, "service", "on_create")
    }

    fun startInitialChain() {
        refreshWakeLock()
        V2KeepAliveScheduler.schedule(service)
        V2KeepAliveReceiver.registerDynamic(service)
    }

    fun recordStartCommand(action: String?) {
        V2KeepAliveStatus.recordTrigger(service, "service", action ?: "start_command")
        ensureChain("start_command")
    }

    fun handleDestroy() {
        V2KeepAliveStatus.recordServiceDestroyed(service)
        val keepAlive = keepAlivePolicy()
        val startup = startupPolicy()
        val shouldRestart = !manualShutdown && keepAlive.enabled && startup.autoStartOnBoot
        V2KeepAliveReceiver.unregisterDynamic(service)
        releaseWakeLock()
        if (shouldRestart) scheduleServiceRestart("on_destroy")
    }

    fun handleTaskRemoved(): Boolean {
        if (manualShutdown) {
            V2AppLog.i(TAG, "task removed ignored: manual shutdown")
            return false
        }
        V2AppLog.w(TAG, "task removed, requesting service restart")
        scheduleServiceRestart("task_removed")
        return true
    }

    fun markManualShutdown() {
        manualShutdown = true
    }

    fun refreshWakeLock() = wakeLockHolder.acquire(startupPolicy(), keepAlivePolicy())

    private fun releaseWakeLock() = wakeLockHolder.release()

    private fun ensureChain(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (isDisplayPowerOn() && now - lastKeepAliveChainMs < KEEP_ALIVE_CHAIN_INTERVAL_MS) {
            refreshWakeLock()
            return
        }
        lastKeepAliveChainMs = now
        releaseCamerasIfSystemAlreadyNonInteractive("keep_alive:$reason")
        V2KeepAliveReceiver.registerTimeTick(service)
        V2KeepAliveScheduler.schedule(service)
        V2KeepAliveStatus.recordTrigger(service, "chain", reason)
        refreshWakeLock()
    }

    private fun scheduleServiceRestart(reason: String) {
        V2KeepAliveStatus.recordTrigger(service, "service_restart", reason)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                V2CameraServiceCommands.start(service.applicationContext)
                V2AppLog.w(TAG, "delayed restart requested reason=$reason")
            }.onFailure { V2AppLog.e(TAG, "delayed restart failed reason=$reason", it) }
        }, SERVICE_RESTART_DELAY_MS)
        V2KeepAliveReceiver.sendKeepAliveCheck(service.applicationContext)
    }

    private companion object {
        private const val TAG = "V2CameraService"
        private const val SERVICE_RESTART_DELAY_MS = 1_000L
        private const val KEEP_ALIVE_CHAIN_INTERVAL_MS = 60_000L
    }
}
