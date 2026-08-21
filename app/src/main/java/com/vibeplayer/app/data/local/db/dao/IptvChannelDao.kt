package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvChannelDao {

    @Query("SELECT * FROM iptv_channels WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    fun observeByPlaylist(playlistId: String): Flow<List<IptvChannelEntity>>

    @Query("SELECT * FROM iptv_channels WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    suspend fun getByPlaylist(playlistId: String): List<IptvChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<IptvChannelEntity>)

    @Query("DELETE FROM iptv_channels WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)
}
