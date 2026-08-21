package com.vibeplayer.app.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written Room migrations for [VibePlayerDatabase].
 *
 * The database uses `fallbackToDestructiveMigration()` as a last-resort safety
 * net, but each known version boundary gets an explicit migration here so a
 * schema change does not silently wipe user data (history, stats, IPTV, etc.).
 */
object Migrations {

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
