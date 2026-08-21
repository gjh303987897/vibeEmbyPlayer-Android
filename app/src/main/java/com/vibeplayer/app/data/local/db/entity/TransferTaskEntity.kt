package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Transfer direction. */
enum class TransferType {
    DOWNLOAD,
    UPLOAD
}

/** Lifecycle of a transfer task. Mirrors the Qt TransferManager queue states. */
enum class TransferStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELED
}

/**
 * A single file transfer (download or upload) tracked by the background
 * transfer queue. One row corresponds to one file; the Transfers page renders
 * progress and lifecycle controls from this persisted state.
 */
@Entity(
    tableName = "transfer_task",
    indices = [Index("created_at_ms"), Index("status")]
)
data class TransferTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "type") val type: TransferType,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "server_name") val serverName: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "local_dest") val localDest: String,
    @ColumnInfo(name = "status") val status: TransferStatus,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long
)
