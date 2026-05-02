package com.kooo.evcam.v2.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2PlaybackListCache
import java.io.File
import java.nio.ByteBuffer

object V2EmergencyClipExtractor {
    fun extract(
        context: Context,
        outputDir: File,
        clipStartWallClockMs: Long,
        clipEndWallClockMs: Long,
        sources: List<V2EmergencySourceSegment>,
    ): File? = runCatching {
        val usableSources = sources
            .filter { it.file.isFile && it.file.length() > 0L && it.endWallClockMs > clipStartWallClockMs && it.startWallClockMs < clipEndWallClockMs }
            .sortedBy { it.startWallClockMs }
        if (usableSources.isEmpty()) return null

        outputDir.mkdirs()
        val finalFile = V2SegmentFileNamer.uniqueFile(outputDir, V2SegmentFileNamer.timestamp(clipStartWallClockMs), EVENT_SUFFIX)
        val tempFile = File(outputDir, finalFile.name + ".recording")
        tempFile.delete()

        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var nextPresentationUs = 0L
        var writtenSamples = 0L
        var muxerStopOk = false
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            for (source in usableSources) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.file.absolutePath)
                    val trackIndex = selectVideoTrack(extractor) ?: continue
                    val format = extractor.getTrackFormat(trackIndex)
                    if (muxer == null) {
                        muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            muxerTrack = it.addTrack(format)
                            it.start()
                        }
                    }
                    extractor.selectTrack(trackIndex)
                    val sourceClipStartUs = ((clipStartWallClockMs - source.startWallClockMs).coerceAtLeast(0L)) * 1000L
                    val sourceClipEndUs = ((clipEndWallClockMs - source.startWallClockMs).coerceAtMost(source.endWallClockMs - source.startWallClockMs)) * 1000L
                    if (sourceClipEndUs <= 0L || sourceClipStartUs >= sourceClipEndUs) continue
                    extractor.seekTo(sourceClipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    var baseSampleTimeUs = Long.MIN_VALUE
                    var lastWrittenRelativeUs = Long.MIN_VALUE
                    val frameDurationUs = frameDurationUs(format)
                    while (true) {
                        val sampleTrack = extractor.sampleTrackIndex
                        if (sampleTrack < 0) break
                        if (sampleTrack != trackIndex) {
                            extractor.advance()
                            continue
                        }
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs >= sourceClipEndUs) break
                        if (baseSampleTimeUs == Long.MIN_VALUE) baseSampleTimeUs = sampleTimeUs
                        val relativeUs = (sampleTimeUs - baseSampleTimeUs).coerceAtLeast(0L)

                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize > 0) {
                            bufferInfo.set(0, sampleSize, nextPresentationUs + relativeUs, extractor.sampleFlags)
                            muxer?.writeSampleData(muxerTrack, buffer, bufferInfo)
                            writtenSamples += 1
                            lastWrittenRelativeUs = relativeUs
                        }
                        extractor.advance()
                    }
                    if (lastWrittenRelativeUs != Long.MIN_VALUE) {
                        nextPresentationUs += lastWrittenRelativeUs + frameDurationUs
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            muxerStopOk = runCatching { muxer?.stop() }
                .onFailure { V2AppLog.w(TAG, "event muxer stop failed", it) }
                .isSuccess
            runCatching { muxer?.release() }
        }

        if (!muxerStopOk || writtenSamples <= 0L || !tempFile.isFile || tempFile.length() <= 0L) {
            tempFile.delete()
            return null
        }
        finalFile.delete()
        val out = if (tempFile.renameTo(finalFile)) finalFile else tempFile
        V2PlaybackListCache.upsertVideo(context.applicationContext, out)
        V2RecordingThumbnailer.generateFirstFrameAsync(context.applicationContext, out)
        V2AppLog.i(TAG, "emergency clip generated file=${out.name} sources=${usableSources.size} samples=$writtenSamples")
        out
    }.onFailure {
        V2AppLog.e(TAG, "emergency clip extraction failed", it)
    }.getOrNull()

    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun frameDurationUs(format: MediaFormat): Long {
        val fps = runCatching {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 24
        }.getOrDefault(24).coerceAtLeast(1)
        return 1_000_000L / fps
    }

    private const val TAG = "EmergencyClipExtractor"
    private const val EVENT_SUFFIX = "_event"
    private const val SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
}
