package com.kooo.evcam.v2.service.status

internal data class V2ServiceStateSnapshot(
    val status: String,
    val normalRecording: Boolean,
    val anyRecording: Boolean,
)
