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
