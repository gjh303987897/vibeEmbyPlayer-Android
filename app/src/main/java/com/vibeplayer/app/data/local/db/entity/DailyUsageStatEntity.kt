package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Daily watch + network traffic statistics, ported from the Qt DailyUsageStat.
 * Composite primary key: date + service + privacy mode.
 */
@Entity(
    tableName = "daily_usage_stats",
    primaryKeys = ["stat_date", "service_id", "privacy_mode"],
    indices = [Index("stat_date")]
)
data class DailyUsageStatEntity(
    @ColumnInfo(name = "stat_date") val date: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "service_name") val serviceName: String,
    @ColumnInfo(name = "service_type") val serviceType: String,
    @ColumnInfo(name = "watch_seconds") val watchSeconds: Long,
    @ColumnInfo(name = "network_bytes_in") val networkBytesIn: Long,
    @ColumnInfo(name = "network_bytes_out") val networkBytesOut: Long,
    @ColumnInfo(name = "keep_alive_network_bytes_in") val keepAliveNetworkBytesIn: Long,
    @ColumnInfo(name = "keep_alive_network_bytes_out") val keepAliveNetworkBytesOut: Long,
    @ColumnInfo(name = "privacy_mode") val privacyMode: Boolean
)
