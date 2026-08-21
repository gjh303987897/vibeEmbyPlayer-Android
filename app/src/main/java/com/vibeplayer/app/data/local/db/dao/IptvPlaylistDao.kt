package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.IptvPlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvPlaylistDao {

    @Query("SELECT * FROM iptv_playlists ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<IptvPlaylistEntity>>

    @Query("SELECT * FROM iptv_playlists WHERE service_id = :serviceId LIMIT 1")
    suspend fun findByServiceId(serviceId: String): IptvPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: IptvPlaylistEntity)

    @Query("DELETE FROM iptv_playlists WHERE service_id = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)
}
