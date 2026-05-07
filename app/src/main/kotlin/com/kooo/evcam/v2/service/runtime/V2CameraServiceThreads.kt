package com.kooo.evcam.v2.service.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

internal class V2CameraServiceThreads {
    val mainHandler: Handler = Handler(Looper.getMainLooper())

    private val workerThread = HandlerThread("V2CameraServiceWorker", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    val workerHandler: Handler = Handler(workerThread.looper)

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun shutdown() {
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }
}
