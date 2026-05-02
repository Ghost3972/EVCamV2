package com.kooo.evcam.v2.recording

import android.view.Surface
import java.io.File

internal interface V2SegmentWriter {
    val backend: V2RecordingBackend
    val surface: Surface?

    fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File
    fun markAttached(segmentIndex: Int, attachedWallClockMs: Long = System.currentTimeMillis())
    fun requestDrain()
    fun finishAndReleaseBlocking(generateThumbnail: Boolean = true, timeoutMs: Long = 10_000L): File?
    fun releaseBlocking(generateThumbnail: Boolean = false, timeoutMs: Long = 1500L): File?
    fun currentFile(): File?
    fun segmentWallClockMs(): Long
    fun mediaStartWallClockMs(): Long
    fun currentSizeBytes(): Long
}
