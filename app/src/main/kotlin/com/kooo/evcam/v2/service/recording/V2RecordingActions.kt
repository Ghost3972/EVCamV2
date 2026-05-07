package com.kooo.evcam.v2.service.recording

internal interface V2RecordingActions {
    fun toggleRecording(): Boolean
    fun startRecording()
    fun stopRecording()
}
