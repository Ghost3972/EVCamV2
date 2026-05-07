package com.kooo.evcam.v2.service.status

internal class V2ServiceStateStore(
    initialStatus: String,
    initialNormalRecording: Boolean,
    initialAnyRecording: Boolean,
) {
    @Volatile private var snapshot = V2ServiceStateSnapshot(
        status = initialStatus,
        normalRecording = initialNormalRecording,
        anyRecording = initialAnyRecording,
    )

    val statusText: String
        get() = snapshot.status

    val isNormalRecording: Boolean
        get() = snapshot.normalRecording

    val isRecording: Boolean
        get() = snapshot.anyRecording

    fun update(status: String, normalRecording: Boolean, anyRecording: Boolean) {
        snapshot = V2ServiceStateSnapshot(
            status = status,
            normalRecording = normalRecording,
            anyRecording = anyRecording,
        )
    }
}
