package com.kooo.evcam.v2.service

import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog

class V2PreviewLeaseManager(
    private val slotCount: Int,
    private val isDisplayPowerOn: () -> Boolean,
    private val attachNative: (Int, Surface, Owner) -> Unit,
    private val detachNative: (Int) -> Unit,
    private val onLeasesChanged: () -> Unit,
) {
    enum class Owner { MAIN, BLIND_SPOT, FISHEYE }

    private val owners = arrayOfNulls<Owner>(slotCount)

    fun attach(index: Int, surface: Surface, owner: Owner) {
        if (index !in owners.indices) return
        val currentOwner = owners[index]
        if (currentOwner != null && currentOwner != owner) {
            if (priority(owner) <= priority(currentOwner)) {
                V2AppLog.i(TAG, "preview attach skipped: owner=$owner current=$currentOwner index=$index")
                return
            }
            V2AppLog.i(TAG, "preview owner takeover: owner=$owner current=$currentOwner index=$index")
            detachNative(index)
            owners[index] = null
        }
        if (owner == Owner.MAIN && currentOwner != null && currentOwner != Owner.MAIN) {
            V2AppLog.i(TAG, "main preview attach cached but skipped: owner=$currentOwner index=$index")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.w(TAG, "attachPreviewSurface skipped: display off owner=$owner index=$index")
            return
        }
        val overlayOwner = owner == Owner.FISHEYE || owner == Owner.BLIND_SPOT
        owners[index] = owner
        if (overlayOwner) onLeasesChanged()
        attachNative(index, surface, owner)
        if (!overlayOwner) onLeasesChanged()
    }

    fun detach(index: Int, owner: Owner) {
        if (index !in owners.indices) return
        val currentOwner = owners[index]
        if (currentOwner != owner) {
            V2AppLog.i(TAG, "detach preview ignored owner=$owner current=$currentOwner index=$index")
            return
        }
        detachNative(index)
        owners[index] = null
        onLeasesChanged()
    }

    fun restoreMain(index: Int, surface: Surface?) {
        surface?.takeIf { it.isValid }?.let { attach(index, it, Owner.MAIN) }
    }

    fun isOwnedBy(index: Int, owner: Owner): Boolean = owners.getOrNull(index) == owner

    fun hasOverlayOwner(): Boolean = owners.any { it == Owner.FISHEYE || it == Owner.BLIND_SPOT }

    private fun priority(owner: Owner): Int = when (owner) {
        Owner.MAIN -> 0
        Owner.FISHEYE -> 1
        Owner.BLIND_SPOT -> 2
    }

    private companion object {
        private const val TAG = "V2CameraService"
    }
}
