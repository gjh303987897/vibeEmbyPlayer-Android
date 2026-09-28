package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Query("SELECT * FROM playback_history WHERE (:showPrivate OR privacy_mode = 0) ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observePaged(showPrivate: Boolean, limit: Int, offset: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE source = :source AND (:showPrivate OR privacy_mode = 0) ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observeBySource(source: String, showPrivate: Boolean, limit: Int, offset: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT COUNT(*) FROM playback_history WHERE (:showPrivate OR privacy_mode = 0)")
    fun observeCount(showPrivate: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_history WHERE source = :source AND (:showPrivate OR privacy_mode = 0)")
    fun observeCountBySource(source: String, showPrivate: Boolean): Flow<Int>

    @Query("SELECT * FROM playback_history WHERE source = :source AND service_id = :serviceId AND replay_target = :replayTarget LIMIT 1")
    suspend fun findByStableIdentity(source: String, serviceId: String, replayTarget: String): PlaybackHistoryEntity?

    @Query("SELECT * FROM playback_history WHERE id = :id")
    suspend fun findById(id: String): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlaybackHistoryEntity)

    @Query(
        "UPDATE playback_history SET position_seconds = :positionSeconds, duration_seconds = :durationSeconds, " +
            "completed = :completed, updated_at_ms = :updatedAtMs WHERE id = :id"
    )
    suspend fun updateProgress(id: String, positionSeconds: Long, durationSeconds: Long, completed: Boolean, updatedAtMs: Long)

    @Query(
        "UPDATE playback_history SET played_date = :playedDate, played_at_ms = :playedAtMs, " +
            "updated_at_ms = :playedAtMs, service_name = :serviceName, title = :title, " +
            "subtitle = :subtitle, display_target = :displayTarget, privacy_mode = :privateMode, available = 1 " +
            "WHERE id = :id"
    )
    suspend fun markPlaybackOccurrence(
        id: String,
        playedDate: String,
        playedAtMs: Long,
        serviceName: String,
        title: String,
        subtitle: String,
        displayTarget: String,
        privateMode: Boolean
    )

    @Query("UPDATE playback_history SET privacy_mode = :privateMode WHERE service_id = :serviceId")
    suspend fun reclassifyService(serviceId: String, privateMode: Boolean)

    @Query("DELETE FROM playback_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM playback_history WHERE service_id = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)

    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun count(): Int
}
