package com.kooo.evcam.v2.ui.blindspot

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

internal data class V2BlindSpotSmallWindowTarget(
    val side: String,
    val cameraIndex: Int,
)

internal object V2BlindSpotSmallWindowIntents {
    private const val EXTRA_SIDE = "com.kooo.evcam.v2.extra.BLIND_SPOT_SIDE"
    private const val EXTRA_CAMERA_INDEX = "com.kooo.evcam.v2.extra.BLIND_SPOT_CAMERA_INDEX"
    private const val EXTRA_WINDOW_MODE = "windowMode"
    private const val START_WINDOW_MODE_KEY = "start_windowmode"
    private const val START_WINDOW_MODE_SMALL = 1
    private const val WINDOW_MODE_FLOATING = 1

    fun targetFrom(intent: Intent?, resolveCameraIndex: (String) -> Int?): V2BlindSpotSmallWindowTarget {
        val side = normalizeSide(intent?.getStringExtra(EXTRA_SIDE))
        var cameraIndex = intent?.getIntExtra(EXTRA_CAMERA_INDEX, -1) ?: -1
        if (cameraIndex < 0) cameraIndex = resolveCameraIndex(side) ?: -1
        return V2BlindSpotSmallWindowTarget(side, cameraIndex)
    }

    fun startIntent(context: Context, side: String, cameraIndex: Int): Intent =
        Intent(context, V2BlindSpotSmallWindowActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_SIDE, normalizeSide(side))
            putExtra(EXTRA_CAMERA_INDEX, cameraIndex)
            putExtra(EXTRA_WINDOW_MODE, WINDOW_MODE_FLOATING)
        }

    fun startOptions() = ActivityOptions.makeBasic().toBundle().apply {
        putInt(START_WINDOW_MODE_KEY, START_WINDOW_MODE_SMALL)
    }

    fun normalizeSide(side: String?): String = if (side == "right") "right" else "left"
}
