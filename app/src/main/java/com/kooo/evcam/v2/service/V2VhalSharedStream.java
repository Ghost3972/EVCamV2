package com.kooo.evcam.v2.service;

import android.util.Log;

import com.kooo.evcam.VhalNative;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import io.grpc.ManagedChannel;

final class V2VhalSharedStream {
    interface BatchListener { void onBatch(byte[] data); }

    private static final String TAG = "V2VhalSharedStream";
    private static final String CLIENT_ID = "evcam_vhal_shared_stream";
    private static final long RECONNECT_DELAY_MS = 3000L;
    private static final V2VhalSharedStream INSTANCE = new V2VhalSharedStream();

    static V2VhalSharedStream get() { return INSTANCE; }

    private final V2VhalStreamClient streamClient = new V2VhalStreamClient(
            TAG,
            CLIENT_ID,
            "connected session_id=",
            "Stream idle timeout, reconnecting",
            "Requested all property values (attempt ",
            "SendAll exhausted all retries"
    );
    private final Set<BatchListener> listeners = new HashSet<>();
    private ManagedChannel channel;
    private Thread thread;
    private boolean running;

    private V2VhalSharedStream() {}

    synchronized void register(BatchListener listener) {
        listeners.add(listener);
        if (!running) startLocked();
    }

    synchronized void unregister(BatchListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) stopLocked();
    }

    private void startLocked() {
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, shared stream disabled");
            return;
        }
        running = true;
        thread = new Thread(this::connectLoop, "V2VhalSharedStream");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopLocked() {
        running = false;
        disconnect();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void connectLoop() {
        while (isRunning()) {
            try {
                V2VhalStreamClient.Connection connection = streamClient.connect();
                channel = connection.channel;
                Log.d(TAG, "shared stream started session_id=" + connection.sessionId + " listeners=" + listenerCount());
                streamClient.streamProperties(VhalNative.getStreamMethod(), channel, new V2VhalStreamClient.Callback() {
                    @Override public void onBatch(byte[] data) { dispatch(data); }
                    @Override public void onStreamCompleted() { Log.d(TAG, "shared stream completed"); }
                    @Override public void onStreamError(Throwable t) { Log.e(TAG, "shared stream error: " + t.getMessage(), t); }
                });
            } catch (Throwable error) {
                Log.e(TAG, "shared stream connection error: " + error.getMessage(), error);
            }
            disconnect();
            if (!isRunning()) break;
            try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ignored) { break; }
        }
    }

    private synchronized boolean isRunning() { return running; }
    private synchronized int listenerCount() { return listeners.size(); }

    private void dispatch(byte[] data) {
        ArrayList<BatchListener> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(listeners); }
        for (BatchListener listener : snapshot) {
            try { listener.onBatch(data); }
            catch (Throwable error) { Log.e(TAG, "listener failed: " + error.getMessage(), error); }
        }
    }

    private void disconnect() {
        ManagedChannel old = channel;
        channel = null;
        streamClient.disconnect(old);
    }
}
