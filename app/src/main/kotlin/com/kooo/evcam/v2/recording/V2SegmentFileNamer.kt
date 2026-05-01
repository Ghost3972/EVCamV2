package com.kooo.evcam.v2.recording

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object V2SegmentFileNamer {
    fun timestamp(wallClockMs: Long): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(wallClockMs))

    fun uniqueFile(outputDir: File, timestamp: String, suffix: String = ""): File {
        var index = 0
        while (true) {
            val name = if (index == 0) {
                String.format(Locale.US, "%s%s.mp4", timestamp, suffix)
            } else {
                String.format(Locale.US, "%s%s_%03d.mp4", timestamp, suffix, index)
            }
            val file = File(outputDir, name)
            val temp = File(outputDir, file.name + ".recording")
            if (!file.exists() && !temp.exists()) return file
            index++
        }
    }
}
