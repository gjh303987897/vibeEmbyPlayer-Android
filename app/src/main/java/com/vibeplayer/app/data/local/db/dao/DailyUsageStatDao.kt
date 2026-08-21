package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.DailyUsageStatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageStatDao {

    @Query("SELECT * FROM daily_usage_stats ORDER BY stat_date DESC")
    fun observeAll(): Flow<List<DailyUsageStatEntity>>

    @Query("SELECT * FROM daily_usage_stats WHERE stat_date = :date AND service_id = :serviceId AND privacy_mode = :privacyMode LIMIT 1")
    suspend fun findByKey(date: String, serviceId: String, privacyMode: Boolean): DailyUsageStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DailyUsageStatEntity)

    @Query(
        "UPDATE daily_usage_stats SET watch_seconds = watch_seconds + :watchSeconds, " +
            "network_bytes_in = network_bytes_in + :networkBytesIn, " +
            "network_bytes_out = network_bytes_out + :networkBytesOut, " +
            "keep_alive_network_bytes_in = keep_alive_network_bytes_in + :keepAliveIn, " +
            "keep_alive_network_bytes_out = keep_alive_network_bytes_out + :keepAliveOut " +
            "WHERE stat_date = :date AND service_id = :serviceId AND privacy_mode = :privacyMode"
    )
    suspend fun increment(
        date: String,
        serviceId: String,
        privacyMode: Boolean,
        watchSeconds: Long,
        networkBytesIn: Long,
        networkBytesOut: Long,
        keepAliveIn: Long,
        keepAliveOut: Long
    )

    @Query("DELETE FROM daily_usage_stats WHERE stat_date < :cutoffDate")
    suspend fun pruneBefore(cutoffDate: String)

    @Query("SELECT COALESCE(SUM(network_bytes_in), 0) FROM daily_usage_stats")
    suspend fun totalBytesIn(): Long
}
