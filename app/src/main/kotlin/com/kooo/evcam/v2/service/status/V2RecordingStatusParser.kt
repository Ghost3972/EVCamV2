package com.kooo.evcam.v2.service.status

internal object V2RecordingStatusParser {
    fun parse(status: String): Boolean? {
        val prefix = status.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            prefix.startsWith("rec=ON") -> true
            prefix.startsWith("rec=OFF") -> false
            else -> null
        }
    }
}
