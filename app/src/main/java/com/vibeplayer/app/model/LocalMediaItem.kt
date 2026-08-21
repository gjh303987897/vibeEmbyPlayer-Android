package com.vibeplayer.app.model

/** A single local media directory entry, ported from the Qt LocalMediaItem. */
data class LocalMediaItem(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)
