package com.kooo.evcam.v2.ui.blindspot

import org.junit.Assert.assertEquals
import org.junit.Test

class V2BlindSpotSmallWindowTransformStoreTest {
    @Test
    fun fixedRotationMatchesSideCameraOrientation() {
        assertEquals(270, V2BlindSpotSmallWindowTransformStore.fixedRotationForSide("left"))
        assertEquals(90, V2BlindSpotSmallWindowTransformStore.fixedRotationForSide("right"))
    }
}
