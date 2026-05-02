package com.kooo.evcam.v2.service

import android.util.Log
import com.kooo.evcam.VhalNative
import io.grpc.ManagedChannel

internal class V2VhalSharedStream private constructor() {
    fun interface BatchListener {
        fun onBatch(data: ByteArray)
    }

    private val streamClient = V2VhalStreamClient(
        tag = TAG,
        clientId = CLIENT_ID,
        connectedLog = "connected session_id=",
        streamTimeoutLog = "Stream idle timeout, reconnecting",
        sendAllSuccessLog = "Requested all property values (attempt ",
        sendAllExhaustedLog = "SendAll exhausted all retries",
    )
    private val listeners = HashSet<BatchListener>()
    private var channel: ManagedChannel? = null
    private var thread: Thread? = null
    private var running = false

    @Synchronized
    fun register(listener: BatchListener) {
        listeners.add(listener)
        if (!running) startLocked()
    }

    @Synchronized
    fun unregister(listener: BatchListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stopLocked()
    }

    private fun startLocked() {
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, shared stream disabled")
            return
        }
        running = true
        thread = Thread(::connectLoop, "V2VhalSharedStream").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLocked() {
        running = false
        disconnect()
        thread?.interrupt()
        thread = null
    }

    private fun connectLoop() {
        while (isRunning()) {
            try {
                val connection = streamClient.connect()
                channel = connection.channel
                Log.d(TAG, "shared stream started session_id=${connection.sessionId} listeners=${listenerCount()}")
                streamClient.streamProperties(
                    streamMethodName = VhalNative.getStreamMethod(),
                    sendAllMethodName = VhalNative.getSendAllMethod(),
                    active = connection.channel,
                    callback = object : V2VhalStreamClient.Callback {
                        override fun onBatch(data: ByteArray) = dispatch(data)
                        override fun onStreamCompleted() {
                            Log.d(TAG, "shared stream completed")
                        }
                        override fun onStreamError(t: Throwable) {
                            Log.e(TAG, "shared stream error: ${t.message}", t)
                        }
                    },
                )
            } catch (error: Throwable) {
                Log.e(TAG, "shared stream connection error: ${error.message}", error)
            }
            disconnect()
            if (!isRunning()) break
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    @Synchronized
    private fun isRunning(): Boolean = running

    @Synchronized
    private fun listenerCount(): Int = listeners.size

    private fun dispatch(data: ByteArray) {
        val snapshot = synchronized(this) { ArrayList(listeners) }
        for (listener in snapshot) {
            try {
                listener.onBatch(data)
            } catch (error: Throwable) {
                Log.e(TAG, "listener failed: ${error.message}", error)
            }
        }
    }

    private fun disconnect() {
        val old = channel
        channel = null
        streamClient.disconnect(old)
    }

    companion object {
        private const val TAG = "V2VhalSharedStream"
        private const val CLIENT_ID = "evcam_vhal_shared_stream"
        private const val RECONNECT_DELAY_MS = 3000L
        private val INSTANCE = V2VhalSharedStream()

        fun get(): V2VhalSharedStream = INSTANCE
    }
}
