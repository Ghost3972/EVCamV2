package com.kooo.evcam.v2.service.commands

internal class V2CameraServiceActionRouter(
    private val scheduleAutoRecording: () -> Unit,
    private val settingsChanged: (String?) -> Unit,
    private val showFisheyePreview: (Int) -> Unit,
    private val hideFisheyePreview: () -> Unit,
    private val showBlindSpotPreview: (String) -> Unit,
    private val hideBlindSpotPreview: () -> Unit,
    private val toggleRecordingFromPlugin: () -> Unit,
    private val displayOff: (String?) -> Unit,
    private val displayOn: (String?) -> Unit,
) {
    fun route(action: V2CameraServiceAction?) {
        when (action) {
            null -> Unit
            V2CameraServiceAction.AutoStartRecording -> scheduleAutoRecording()
            is V2CameraServiceAction.SettingsChanged -> settingsChanged(action.category)
            is V2CameraServiceAction.ShowFisheyePreview -> showFisheyePreview(action.cameraIndex)
            V2CameraServiceAction.HideFisheyePreview -> hideFisheyePreview()
            is V2CameraServiceAction.ShowBlindSpotPreview -> showBlindSpotPreview(action.side)
            V2CameraServiceAction.HideBlindSpotPreview -> hideBlindSpotPreview()
            V2CameraServiceAction.ToggleRecordingFromPlugin -> toggleRecordingFromPlugin()
            is V2CameraServiceAction.DisplayOff -> displayOff(action.rawAction)
            is V2CameraServiceAction.DisplayOn -> displayOn(action.rawAction)
        }
    }
}
