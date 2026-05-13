package com.kooo.evcam.v2.service.commands

import com.kooo.evcam.v2.settings.V2SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2CameraServiceActionParserTest {
    @Test
    fun settingsChangedKeepsExplicitCategory() {
        val action = V2CameraServiceActionParser.fromFields(
            action = V2CameraServiceContract.ACTION_SETTINGS_CHANGED,
            settingsCategory = V2SettingsCategory.AVOIDANCE,
        )

        assertEquals(V2CameraServiceAction.SettingsChanged(V2SettingsCategory.AVOIDANCE), action)
    }

    @Test
    fun shortcutRefreshActionsMapToSettingsCategories() {
        assertEquals(
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.CUSTOM_KEY),
            V2CameraServiceActionParser.fromFields(V2CameraServiceContract.ACTION_REFRESH_CUSTOM_KEY),
        )
        assertEquals(
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.BLIND_SPOT),
            V2CameraServiceActionParser.fromFields(V2CameraServiceContract.ACTION_REFRESH_BLIND_SPOT),
        )
        assertEquals(
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.FISHEYE),
            V2CameraServiceActionParser.fromFields(V2CameraServiceContract.ACTION_REFRESH_FISHEYE),
        )
        assertEquals(
            V2CameraServiceAction.SettingsChanged(V2SettingsCategory.WAKE_LOCK),
            V2CameraServiceActionParser.fromFields(V2CameraServiceContract.ACTION_REFRESH_WAKE_LOCK),
        )
    }

    @Test
    fun previewActionsCarryArgumentsWithDefaults() {
        assertEquals(
            V2CameraServiceAction.ShowFisheyePreview(2),
            V2CameraServiceActionParser.fromFields(
                action = V2CameraServiceContract.ACTION_SHOW_FISHEYE_PREVIEW,
                cameraIndex = 2,
            ),
        )
        assertEquals(
            V2CameraServiceAction.ShowBlindSpotPreview(V2CameraServiceContract.DEFAULT_BLIND_SPOT_SIDE),
            V2CameraServiceActionParser.fromFields(V2CameraServiceContract.ACTION_SHOW_BLIND_SPOT_PREVIEW),
        )
        assertEquals(
            V2CameraServiceAction.ShowBlindSpotPreview("right"),
            V2CameraServiceActionParser.fromFields(
                action = V2CameraServiceContract.ACTION_SHOW_BLIND_SPOT_PREVIEW,
                side = "right",
            ),
        )
    }

    @Test
    fun unknownActionIsIgnored() {
        assertNull(V2CameraServiceActionParser.fromFields("com.kooo.evcam.v2.action.UNKNOWN"))
        assertNull(V2CameraServiceActionParser.fromFields(null))
    }
}
