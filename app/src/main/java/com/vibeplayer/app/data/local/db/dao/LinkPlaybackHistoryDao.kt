package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkPlaybackHistoryDao {

    @Query("SELECT * FROM link_playback_history ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observePaged(limit: Int, offset: Int): Flow<List<LinkPlaybackHistoryEntity>>

    @Query("SELECT * FROM link_playback_history WHERE playback_url = :url LIMIT 1")
    suspend fun findByUrl(url: String): LinkPlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LinkPlaybackHistoryEntity)

    @Query("DELETE FROM link_playback_history WHERE id = :id")
    suspend fun deleteById(id: String)
}
