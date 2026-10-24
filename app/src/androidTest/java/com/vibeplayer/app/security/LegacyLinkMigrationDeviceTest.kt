package com.vibeplayer.app.security

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.local.db.Migrations
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyLinkMigrationDeviceTest {
    @Test fun roomUpgradePreservesProgressAndQuarantinesLegacyLinkData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "security-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        val schema = instrumentation.context.assets.open(
            "com.vibeplayer.app.data.local.db.VibePlayerDatabase/3.json",
        ).bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val entities = schema.getJSONArray("entities").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("tableName") }
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                entities.forEach { (table, entity) ->
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (i in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                fun insert(table: String, vararg overrides: Pair<String, Any>) {
                    val fields = entities.getValue(table).getJSONArray("fields")
                    val values = ContentValues()
                    for (i in 0 until fields.length()) {
                        val field = fields.getJSONObject(i)
                        val column = field.getString("columnName")
                        if (field.getString("affinity") == "TEXT") values.put(column, "") else values.put(column, 0L)
                    }
                    overrides.forEach { (column, value) -> when (value) {
                        is String -> values.put(column, value)
                        is Int -> values.put(column, value)
                        is Long -> values.put(column, value)
                        else -> error("Unsupported synthetic value")
                    } }
                    assertTrue(old.insertOrThrow(table, null, values) >= 0)
                }
                for ((id, private) in listOf("legacy-public" to 0, "legacy-private" to 1)) {
                    insert("playback_history", "id" to id, "source" to "Link", "replay_target" to id, "privacy_mode" to private,
                        "position_seconds" to 25L, "duration_seconds" to 100L, "available" to 1)
                    insert("link_playback_history", "id" to id, "playback_url" to id, "privacy_mode" to private)
                }
                insert("playback_history", "id" to "public-dav", "source" to "WebDAV", "service_id" to "dav")
                for ((private, watch) in listOf(0 to 10, 1 to 20)) {
                    insert("daily_usage_stats", "stat_date" to "2026-10-25", "service_id" to "builtin-link-playback",
                        "privacy_mode" to private, "watch_seconds" to watch, "network_bytes_in" to watch * 10)
                }
                insert("daily_usage_stats", "stat_date" to "2026-10-25", "service_id" to "dav", "watch_seconds" to 99)
                old.version = 3
            }
            val room = Room.databaseBuilder(context, VibePlayerDatabase::class.java, name)
                .addMigrations(Migrations.MIGRATION_3_4).build()
            try {
                val db = room.openHelper.writableDatabase // Executes migration and Room schema validation.
                assertEquals(4, db.version)
                assertEquals(2L, db.scalar("SELECT COUNT(*) FROM playback_history WHERE service_id='legacy-link-playback' AND privacy_mode=1 AND position_seconds=25 AND duration_seconds=100"))
                assertEquals(2L, db.scalar("SELECT COUNT(*) FROM link_playback_history WHERE privacy_mode=1"))
                assertEquals(0L, db.scalar("SELECT privacy_mode FROM playback_history WHERE id='public-dav'"))
                db.query("SELECT watch_seconds,network_bytes_in,privacy_mode FROM daily_usage_stats WHERE service_id='legacy-link-playback'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(30L, it.getLong(0)); assertEquals(300L, it.getLong(1)); assertEquals(1, it.getInt(2))
                    assertFalse(it.moveToNext())
                }
                assertEquals(99L, db.scalar("SELECT watch_seconds FROM daily_usage_stats WHERE service_id='dav'"))
                db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            } finally { room.close() }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }

    private fun SupportSQLiteDatabase.scalar(sql: String) = query(sql).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
}
