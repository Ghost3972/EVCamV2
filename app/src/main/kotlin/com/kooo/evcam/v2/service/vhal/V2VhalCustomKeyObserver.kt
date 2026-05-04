package com.kooo.evcam.v2.service.vhal

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kooo.evcam.VhalNative

class V2VhalCustomKeyObserver(
    private val buttonPropId: Int,
    private val listener: Listener,
) {
    fun interface Listener {
        fun onCustomKeyValue4()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private val batchListener = V2VhalSharedStream.BatchListener { data ->
        if (running) processPropertyBatch(data)
    }
    @Volatile private var lastButtonState = -1
    @Volatile private var batchCount = 0L
    @Volatile private var emptyBatchCount = 0L
    @Volatile private var customKeyEventCount = 0L

    @Synchronized
    fun start() {
        if (running) return
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, custom key observer disabled")
            return
        }
        running = true
        lastButtonState = -1
        batchCount = 0
        emptyBatchCount = 0
        customKeyEventCount = 0
        Log.d(TAG, "configure custom key buttonPropId=$buttonPropId")
        VhalNative.configureCustomKey(DEFAULT_SPEED_PROP_ID, buttonPropId, 0f)
        V2VhalSharedStream.get().register(batchListener)
        Log.d(TAG, "registered shared VHAL stream listener")
    }

    @Synchronized
    fun stop() {
        running = false
        V2VhalSharedStream.get().unregister(batchListener)
        Log.d(TAG, "unregistered shared VHAL stream listener")
    }

    private fun processPropertyBatch(data: ByteArray) {
        if (!running) return
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, skipping property batch processing")
            return
        }
        batchCount++
        val events = V2VhalEventDecoder.decode(data, TAG)
        if (events.isEmpty()) {
            emptyBatchCount++
            if (emptyBatchCount == 1L || emptyBatchCount % EMPTY_BATCH_LOG_INTERVAL == 0L) {
                Log.d(TAG, "Decoded empty custom key batch count=$emptyBatchCount total=$batchCount bytes=${data.size}")
            }
            return
        }
        for (event in events) {
            if (event.type == VhalNative.EVT_CUSTOM_KEY) {
                customKeyEventCount++
                Log.d(TAG, "Decoded custom key event count=$customKeyEventCount p1=${event.p1} p2=${event.p2} last=$lastButtonState")
                handleButtonState(event.p1, event.p2)
            }
        }
    }

    private fun handleButtonState(state: Int, extra: Int) {
        if (state == LONG_PRESS_VALUE && lastButtonState != LONG_PRESS_VALUE) {
            Log.d(TAG, "Custom key long press triggered, value=$state extra=$extra events=$customKeyEventCount")
            mainHandler.post(listener::onCustomKeyValue4)
        } else if (state != lastButtonState) {
            Log.d(TAG, "Custom key state changed $lastButtonState -> $state extra=$extra")
        }
        lastButtonState = state
    }

    companion object {
        private const val TAG = "V2VhalCustomKey"
        private const val DEFAULT_SPEED_PROP_ID = 291504647
        private const val LONG_PRESS_VALUE = 4
        private const val EMPTY_BATCH_LOG_INTERVAL = 1000L
    }
}
