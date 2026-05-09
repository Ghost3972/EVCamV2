package com.kooo.evcam.v2.service.commands

internal sealed interface V2CameraServiceAction {
    data object AutoStartRecording : V2CameraServiceAction
    data class SettingsChanged(val category: String?) : V2CameraServiceAction
    data class ShowFisheyePreview(val cameraIndex: Int) : V2CameraServiceAction
    data object HideFisheyePreview : V2CameraServiceAction
    data class ShowBlindSpotPreview(val side: String) : V2CameraServiceAction
    data object HideBlindSpotPreview : V2CameraServiceAction
    data object ToggleRecordingFromPlugin : V2CameraServiceAction
    data class DisplayOff(val rawAction: String) : V2CameraServiceAction
    data class DisplayOn(val rawAction: String) : V2CameraServiceAction
}
