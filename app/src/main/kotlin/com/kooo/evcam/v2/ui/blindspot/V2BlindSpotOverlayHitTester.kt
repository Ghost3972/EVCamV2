package com.kooo.evcam.v2.ui.blindspot

import android.view.View

internal object V2BlindSpotOverlayHitTester {
    fun isOutsideControls(x: Float, y: Float, vararg controls: View?): Boolean {
        return controls.none { control -> isInViewHit(control, x, y) }
    }

    private fun isInViewHit(view: View?, x: Float, y: Float): Boolean {
        view ?: return false
        return x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
    }
}
