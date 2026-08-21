package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single IPTV channel, ported from the Qt IptvChannel.
 */
@Entity(
    tableName = "iptv_channels",
    indices = [Index("playlist_id")]
)
data class IptvChannelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)
