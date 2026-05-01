package com.kooo.evcam.v2.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcam.VhalNative;

public final class V2VhalTurnSignalObserver {
    public interface Listener { void onTurnSignal(String side, boolean on); }

    private static final String TAG = "V2VhalTurnSignal";
    private static final int EVT_TURN_SIGNAL = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int propId;
    private final int leftValue;
    private final int rightValue;
    private final int offValue;
    private final Listener listener;
    private volatile boolean running;
    private final V2VhalSharedStream.BatchListener batchListener = data -> {
        if (running) processPropertyBatch(data);
    };
    private volatile int lastState = Integer.MIN_VALUE;
    private volatile String lastSide;

    public V2VhalTurnSignalObserver(int propId, int leftValue, int rightValue, int offValue, Listener listener) {
        this.propId = propId;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
        this.offValue = offValue;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, turn signal observer disabled");
            return;
        }
        running = true;
        lastState = Integer.MIN_VALUE;
        lastSide = null;
        Log.d(TAG, "start propId=" + propId + " left=" + leftValue + " right=" + rightValue + " off=" + offValue);
        V2VhalSharedStream.get().register(batchListener);
        Log.d(TAG, "registered shared VHAL stream listener");
    }

    public synchronized void stop() {
        running = false;
        V2VhalSharedStream.get().unregister(batchListener);
        Log.d(TAG, "unregistered shared VHAL stream listener");
    }

    private void processPropertyBatch(byte[] data) {
        if (!running) return;
        int[] events;
        try { events = VhalNative.decode(data); } catch (Throwable error) { Log.e(TAG, "decode failed: " + error.getMessage(), error); return; }
        if (events == null || events.length < 1) return;
        int count = events[0];
        for (int i = 0; i < count; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) break;
            int type = events[offset];
            int value = events[offset + 1];
            int eventPropId = events[offset + 2];
            if (type == EVT_TURN_SIGNAL) {
                Log.d(TAG, "turn signal event value=" + value + " p2=" + eventPropId + " configuredPropId=" + propId);
                if (eventPropId > 0 && eventPropId != propId) {
                    Log.d(TAG, "ignore turn signal event for propId=" + eventPropId + ", expected=" + propId);
                    continue;
                }
                handleTurnSignalValue(value);
            }
        }
    }

    private void handleTurnSignalValue(int value) {
        if (value == lastState) return;
        String previousSide = lastSide;
        lastState = value;
        String side = value == leftValue ? "left" : value == rightValue ? "right" : null;
        Log.d(TAG, "turn signal value=" + value + " side=" + side + " propId=" + propId);
        if (side != null) {
            lastSide = side;
            mainHandler.post(() -> listener.onTurnSignal(side, true));
        } else if (value == offValue && previousSide != null) {
            lastSide = null;
            mainHandler.post(() -> listener.onTurnSignal(previousSide, false));
        }
    }

}
