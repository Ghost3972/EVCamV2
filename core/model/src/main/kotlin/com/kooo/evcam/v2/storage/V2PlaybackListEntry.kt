package com.kooo.evcam.v2.storage

data class V2PlaybackListEntry(
    val key: String,
    val path: String,
    val length: Long,
    val modified: Long,
    val thumbnailPath: String? = null,
    val thumbnailModified: Long = 0L,
)
