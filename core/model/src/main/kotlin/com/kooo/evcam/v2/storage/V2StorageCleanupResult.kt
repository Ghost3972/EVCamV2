package com.kooo.evcam.v2.storage

data class V2StorageCleanupResult(
    val deletedCount: Int,
    val deletedBytes: Long,
    val availableBytes: Long,
    val reservedBytes: Long
)
