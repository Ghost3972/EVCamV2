package com.kooo.evcam.v2.service.vhal

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kooo.evcam.VhalNative

class V2VhalTurnSignalObserver(
    private val propId: Int,
    private val leftValue: Int,
    private val rightValue: Int,
    private val offValue: Int,
    private val listener: Listener,
) {
    fun interface Listener {
        fun onTurnSignal(side: String, on: Boolean)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private val batchListener = V2VhalSharedStream.BatchListener { data ->
        if (running) processPropertyBatch(data)
    }
    @Volatile private var lastState = Int.MIN_VALUE
    @Volatile private var lastSide: String? = null

    @Synchronized
    fun start() {
        if (running) return
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, turn signal observer disabled")
            return
        }
        running = true
        lastState = Int.MIN_VALUE
        lastSide = null
        Log.d(TAG, "start propId=$propId left=$leftValue right=$rightValue off=$offValue")
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
        for (event in V2VhalEventDecoder.decode(data, TAG)) {
            if (event.type == EVT_TURN_SIGNAL) {
                val value = event.p1
                val eventPropId = event.p2
                Log.d(TAG, "turn signal event value=$value p2=$eventPropId configuredPropId=$propId")
                if (eventPropId > 0 && eventPropId != propId) {
                    Log.d(TAG, "ignore turn signal event for propId=$eventPropId, expected=$propId")
                    continue
                }
                handleTurnSignalValue(value)
            }
        }
    }

    private fun handleTurnSignalValue(value: Int) {
        if (value == lastState) return
        val previousSide = lastSide
        lastState = value
        val side = when (value) {
            leftValue -> "left"
            rightValue -> "right"
            else -> null
        }
        Log.d(TAG, "turn signal value=$value side=$side propId=$propId")
        if (side != null) {
            lastSide = side
            mainHandler.post { listener.onTurnSignal(side, true) }
        } else if (value == offValue && previousSide != null) {
            lastSide = null
            mainHandler.post { listener.onTurnSignal(previousSide, false) }
        }
    }

    companion object {
        private const val TAG = "V2VhalTurnSignal"
        private const val EVT_TURN_SIGNAL = 1
    }
}
