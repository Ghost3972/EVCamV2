package com.kooo.evcam.v2.service.vhal

import android.util.Log
import com.kooo.evcam.VhalNative

internal object V2VhalEventDecoder {
    data class Event(val type: Int, val p1: Int, val p2: Int)

    fun decode(data: ByteArray, tag: String): List<Event> {
        val values = try {
            VhalNative.decode(data)
        } catch (error: Throwable) {
            Log.e(tag, "decode failed: ${error.message}", error)
            return emptyList()
        }
        if (values == null || values.isEmpty()) return emptyList()

        val count = values[0]
        if (count <= 0) return emptyList()

        val events = ArrayList<Event>(count)
        for (i in 0 until count) {
            val offset = 1 + i * EVENT_WIDTH
            if (offset + 2 >= values.size) break
            events += Event(
                type = values[offset],
                p1 = values[offset + 1],
                p2 = values[offset + 2],
            )
        }
        return events
    }

    private const val EVENT_WIDTH = 3
}
