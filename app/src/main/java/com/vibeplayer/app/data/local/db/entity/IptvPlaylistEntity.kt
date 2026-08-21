package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An imported IPTV M3U/M3U8 playlist, ported from the Qt IptvPlaylist.
 */
@Entity(
    tableName = "iptv_playlists",
    indices = [Index(value = ["service_id"], unique = true)]
)
data class IptvPlaylistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    val name: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "imported_path") val importedPath: String,
    @ColumnInfo(name = "imported_at") val importedAt: String
)
