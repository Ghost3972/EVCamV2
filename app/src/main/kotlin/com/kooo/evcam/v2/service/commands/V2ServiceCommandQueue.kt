package com.kooo.evcam.v2.service.commands

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog

/**
 * Single coarse-grained serializer for service state transitions.
 *
 * The Android shell still owns lifecycle/UI callbacks, but camera/recording/display
 * commands enter the engine through this queue so competing sources do not interleave direct
 * engine operations on the main thread.
 */
internal class V2ServiceCommandQueue(private val handler: Handler) {
    private var nextId = 0L

    fun dispatch(name: String, block: () -> Unit) {
        val id = ++nextId
        handler.post { runCommand(id, name, block) }
    }

    fun dispatchDelayed(name: String, delayMs: Long, token: Any = name, block: () -> Unit) {
        val id = ++nextId
        val runnable = Runnable { runCommand(id, name, block) }
        handler.postAtTime(runnable, token, SystemClock.uptimeMillis() + delayMs)
    }

    fun cancel(token: Any) {
        handler.removeCallbacksAndMessages(token)
    }

    private fun runCommand(id: Long, name: String, block: () -> Unit) {
        val startedMs = SystemClock.elapsedRealtime()
        if (Looper.myLooper() != handler.looper) {
            handler.post { runCommand(id, name, block) }
            return
        }
        runCatching(block)
            .onFailure { V2AppLog.e(TAG, "command failed id=$id name=$name", it) }
        val elapsedMs = SystemClock.elapsedRealtime() - startedMs
        if (elapsedMs >= SLOW_COMMAND_MS) {
            V2AppLog.perf(TAG, "serviceCommand", elapsedMs, "id=$id name=$name")
        }
    }

    private companion object {
        private const val TAG = "V2ServiceCommandQueue"
        private const val SLOW_COMMAND_MS = 100L
    }
}
