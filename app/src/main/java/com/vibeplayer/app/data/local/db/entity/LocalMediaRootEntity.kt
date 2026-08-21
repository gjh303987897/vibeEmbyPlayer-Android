package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-selected local media root folder, ported from the Qt LocalMediaRoot.
 * On Android "path" holds a persisted SAF document URI.
 */
@Entity(tableName = "local_media_roots")
data class LocalMediaRootEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val available: Boolean
)
