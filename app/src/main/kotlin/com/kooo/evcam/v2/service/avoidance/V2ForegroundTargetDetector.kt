package com.kooo.evcam.v2.service.avoidance

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

internal class V2ForegroundTargetDetector(
    private val workerHandler: Handler,
    private val callbackHandler: Handler,
    private val foregroundAppMonitor: V2ForegroundAppMonitor,
) {
    @Volatile private var generation = 0
    @Volatile private var inFlight = false

    fun detect(targets: List<String>, onResult: (String?) -> Unit) {
        if (targets.isEmpty()) {
            onResult(null)
            return
        }
        if (inFlight) return
        val requestGeneration = ++generation
        val requestTargets = targets.toList()
        inFlight = true
        workerHandler.post {
            val result = runCatching { foregroundAppMonitor.findForegroundTarget(requestTargets) }
                .onFailure { V2AppLog.e(TAG, "foreground target detection failed", it) }
                .getOrNull()
            callbackHandler.post {
                if (requestGeneration != generation) return@post
                inFlight = false
                onResult(result)
            }
        }
    }

    fun cancel() {
        generation++
        inFlight = false
    }

    private companion object {
        private const val TAG = "V2ForegroundTargetDetector"
    }
}
