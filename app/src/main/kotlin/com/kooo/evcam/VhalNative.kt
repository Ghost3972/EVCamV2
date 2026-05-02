package com.kooo.evcam

import android.util.Log

/** JNI bridge for the existing vehicle VHAL decoder library. */
class VhalNative private constructor() {
    companion object {
        private const val TAG = "VhalNative"

        @Volatile
        private var libraryLoaded = false

        const val EVT_CUSTOM_KEY: Int = 5

        init {
            try {
                System.loadLibrary("vhal_decoder")
                libraryLoaded = true
                Log.d(TAG, "vhal_decoder loaded")
            } catch (error: Throwable) {
                libraryLoaded = false
                Log.e(TAG, "failed to load vhal_decoder: ${error.message}")
            }
        }

        @JvmStatic
        fun isLibraryLoaded(): Boolean = libraryLoaded

        @JvmStatic external fun getGrpcHost(): String
        @JvmStatic external fun getGrpcPort(): Int
        @JvmStatic external fun getStreamMethod(): String
        @JvmStatic external fun getSendAllMethod(): String
        @JvmStatic external fun decode(data: ByteArray): IntArray?
        @JvmStatic external fun configureCustomKey(speedPropId: Int, buttonPropId: Int, speedThreshold: Float)
    }
}
