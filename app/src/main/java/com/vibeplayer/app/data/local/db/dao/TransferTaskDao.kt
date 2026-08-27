package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vibeplayer.app.data.local.db.entity.TransferStatus
import com.vibeplayer.app.data.local.db.entity.TransferTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferTaskDao {

    @Query("SELECT * FROM transfer_task ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_task WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TransferTaskEntity?

    @Query("SELECT * FROM transfer_task WHERE status = 'QUEUED' OR status = 'RUNNING' ORDER BY created_at_ms ASC")
    suspend fun active(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_task WHERE status = 'QUEUED' ORDER BY created_at_ms ASC")
    suspend fun queued(): List<TransferTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TransferTaskEntity)

    @Update
    suspend fun update(task: TransferTaskEntity)

    @Query("UPDATE transfer_task SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: TransferStatus, error: String?)

    @Query("UPDATE transfer_task SET transferred_bytes = :bytes WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long)

    @Query("UPDATE transfer_task SET status = 'RUNNING', error = NULL WHERE id = :id AND status = 'QUEUED'")
    suspend fun claimQueued(id: String): Int

    @Query("DELETE FROM transfer_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transfer_task WHERE status = 'DONE' OR status = 'FAILED' OR status = 'CANCELED'")
    suspend fun clearFinished()
}
