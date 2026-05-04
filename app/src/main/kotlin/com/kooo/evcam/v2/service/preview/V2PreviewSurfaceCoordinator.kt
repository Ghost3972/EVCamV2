package com.kooo.evcam.v2.service.preview

import android.view.Surface

internal class V2PreviewSurfaceCoordinator(
    slotCount: Int,
    isDisplayPowerOn: () -> Boolean,
    attachNative: (index: Int, surface: Surface, owner: V2PreviewLeaseManager.Owner) -> Unit,
    detachNative: (index: Int) -> Unit,
    onLeasesChanged: () -> Unit,
) {
    val surfaces: Array<Surface?> = arrayOfNulls(slotCount)

    private val leaseManager = V2PreviewLeaseManager(
        slotCount = slotCount,
        isDisplayPowerOn = isDisplayPowerOn,
        attachNative = attachNative,
        detachNative = detachNative,
        onLeasesChanged = onLeasesChanged,
    )

    fun attachMain(index: Int, surface: Surface) {
        surfaces[index] = surface
        leaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.MAIN)
    }

    fun detachMain(index: Int) {
        surfaces[index] = null
        leaseManager.detach(index, V2PreviewLeaseManager.Owner.MAIN)
    }

    fun attachFisheye(index: Int, surface: Surface) {
        leaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.FISHEYE)
    }

    fun detachFisheye(index: Int) {
        leaseManager.detach(index, V2PreviewLeaseManager.Owner.FISHEYE)
    }

    fun attachBlindSpot(index: Int, surface: Surface) {
        leaseManager.attach(index, surface, V2PreviewLeaseManager.Owner.BLIND_SPOT)
    }

    fun detachBlindSpot(index: Int) {
        leaseManager.detach(index, V2PreviewLeaseManager.Owner.BLIND_SPOT)
    }

    fun restoreMain(index: Int) {
        leaseManager.restoreMain(index, surfaces.getOrNull(index))
    }

    fun restoreAllMain() {
        surfaces.forEachIndexed { index, _ -> restoreMain(index) }
    }

    fun isBlindSpotOwner(index: Int): Boolean {
        return leaseManager.isOwnedBy(index, V2PreviewLeaseManager.Owner.BLIND_SPOT)
    }

    fun hasOverlayOwner(): Boolean = leaseManager.hasOverlayOwner()
}
