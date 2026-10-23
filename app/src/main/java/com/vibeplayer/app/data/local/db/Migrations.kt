package com.vibeplayer.app.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written Room migrations for [VibePlayerDatabase].
 *
 * Every known version boundary gets an explicit migration. There is deliberately
 * no destructive fallback: an omitted migration must fail loudly instead of
 * silently deleting history, stats, IPTV, or transfer data.
 */
object Migrations {
    /** Old Link records lost their source identity. Preserve them behind the privacy lock. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE playback_history SET service_id = 'legacy-link-playback', privacy_mode = 1 WHERE source = 'Link' AND (service_id = '' OR service_id = 'builtin-link-playback')")
            db.execSQL("UPDATE link_playback_history SET privacy_mode = 1 WHERE id IN (SELECT id FROM playback_history WHERE service_id = 'legacy-link-playback')")
            db.execSQL("""
                CREATE TEMP TABLE legacy_link_usage AS
                SELECT stat_date, 'legacy-link-playback' AS service_id, service_name, service_type,
                    SUM(watch_seconds) AS watch_seconds, SUM(network_bytes_in) AS network_bytes_in,
                    SUM(network_bytes_out) AS network_bytes_out,
                    SUM(keep_alive_network_bytes_in) AS keep_alive_network_bytes_in,
                    SUM(keep_alive_network_bytes_out) AS keep_alive_network_bytes_out, 1 AS privacy_mode
                FROM daily_usage_stats WHERE service_id = 'builtin-link-playback' GROUP BY stat_date
            """.trimIndent())
            db.execSQL("DELETE FROM daily_usage_stats WHERE service_id = 'builtin-link-playback'")
            db.execSQL("INSERT INTO daily_usage_stats (stat_date, service_id, service_name, service_type, watch_seconds, network_bytes_in, network_bytes_out, keep_alive_network_bytes_in, keep_alive_network_bytes_out, privacy_mode) SELECT * FROM legacy_link_usage")
            db.execSQL("DROP TABLE legacy_link_usage")
        }
    }

    /** Keep legacy route IDs/favourites, while scoping routes to their own playlist. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE iptv_channels_new (
                    id TEXT NOT NULL, playlist_id TEXT NOT NULL, name TEXT NOT NULL,
                    group_name TEXT NOT NULL, logo_url TEXT NOT NULL, stream_url TEXT NOT NULL,
                    sort_order INTEGER NOT NULL, tvg_id TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(playlist_id, id)
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO iptv_channels_new (id, playlist_id, name, group_name, logo_url, stream_url, sort_order)
                SELECT id, playlist_id, name, group_name, logo_url, stream_url, sort_order FROM iptv_channels
            """.trimIndent())
            db.execSQL("DROP TABLE iptv_channels")
            db.execSQL("ALTER TABLE iptv_channels_new RENAME TO iptv_channels")
            db.execSQL("CREATE INDEX index_iptv_channels_playlist_id ON iptv_channels(playlist_id)")
        }
    }

    /**
     * Version 1 → 2.
     *
     * - The `server_cards` table (an old ServerCardEntity that never held
     *   business data) was removed, so drop it if it exists.
     * - A new `transfer_task` table was added for the background transfer queue.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `server_cards`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transfer_task` (
                    `id` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `server_id` TEXT NOT NULL,
                    `server_name` TEXT NOT NULL,
                    `remote_path` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `local_dest` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `total_bytes` INTEGER NOT NULL,
                    `transferred_bytes` INTEGER NOT NULL,
                    `error` TEXT,
                    `created_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transfer_task_created_at_ms` ON `transfer_task` (`created_at_ms`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transfer_task_status` ON `transfer_task` (`status`)"
            )
        }
    }
}
