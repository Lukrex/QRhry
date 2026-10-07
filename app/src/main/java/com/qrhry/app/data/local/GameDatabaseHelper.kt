package com.qrhry.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import java.util.UUID

class GameDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE games (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL CHECK (length(trim(title)) > 0),
                game_uuid TEXT NOT NULL UNIQUE,
                content_version INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE stations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_id INTEGER NOT NULL,
                title TEXT NOT NULL CHECK (length(trim(title)) > 0),
                body_text TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL CHECK (position >= 0),
                qr_token TEXT NOT NULL UNIQUE,
                FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
                UNIQUE (game_id, position)
            )"""
        )
        db.execSQL("CREATE INDEX index_stations_game_id ON stations(game_id)")
        createSessionTables(db, includeVisitCompletion = true)
        createStationMediaTable(db)
        createTaskTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        while (version < newVersion) {
            when (version) {
                1 -> {
                    migrateV1ToV2(db)
                    version = 2
                }
                2 -> {
                    migrateV2ToV3(db)
                    version = 3
                }
                3 -> {
                    migrateV3ToV4(db)
                    version = 4
                }
                4 -> {
                    migrateV4ToV5(db)
                    version = 5
                }
                else -> throw SQLiteException(
                    "No migration is defined from database version $version to $newVersion."
                )
            }
        }
        if (version != newVersion) throw SQLiteException("Unsupported database version $newVersion.")
    }

    private fun migrateV2ToV3(db: SQLiteDatabase) {
        createSessionTables(db, includeVisitCompletion = false)
    }

    private fun migrateV3ToV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE games ADD COLUMN game_uuid TEXT")
        db.execSQL("ALTER TABLE games ADD COLUMN content_version INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE games ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.rawQuery("SELECT id FROM games", null).use { cursor ->
            while (cursor.moveToNext()) {
                db.execSQL(
                    "UPDATE games SET game_uuid = ?, updated_at = ? WHERE id = ?",
                    arrayOf(UUID.randomUUID().toString(), System.currentTimeMillis(), cursor.getLong(0))
                )
            }
        }
        db.execSQL("CREATE UNIQUE INDEX index_games_game_uuid ON games(game_uuid)")
        createStationMediaTable(db)
    }

    private fun migrateV4ToV5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE station_visits ADD COLUMN completed_at INTEGER")
        db.execSQL("UPDATE station_visits SET completed_at = visited_at")
        createTaskTables(db)
    }

    private fun createStationMediaTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE station_media (
                id TEXT PRIMARY KEY NOT NULL,
                station_id INTEGER NOT NULL,
                media_type TEXT NOT NULL CHECK (media_type IN ('IMAGE', 'AUDIO')),
                relative_path TEXT NOT NULL UNIQUE,
                mime_type TEXT NOT NULL,
                original_filename TEXT,
                checksum TEXT NOT NULL,
                byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
                display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
                FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE,
                UNIQUE (station_id, media_type),
                UNIQUE (station_id, display_order)
            )"""
        )
        db.execSQL("CREATE INDEX index_station_media_station_id ON station_media(station_id)")
    }

    private fun createSessionTables(db: SQLiteDatabase, includeVisitCompletion: Boolean) {
        db.execSQL(
            """CREATE TABLE game_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_id INTEGER NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL(
            """CREATE UNIQUE INDEX index_game_sessions_one_active_per_game
                ON game_sessions(game_id) WHERE status = 'IN_PROGRESS'"""
        )
        db.execSQL(
            """CREATE TABLE station_visits (
                session_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL,
                visited_at INTEGER NOT NULL,
                ${if (includeVisitCompletion) "completed_at INTEGER," else ""}
                PRIMARY KEY (session_id, station_id),
                FOREIGN KEY (session_id) REFERENCES game_sessions(id) ON DELETE CASCADE,
                FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX index_station_visits_station_id ON station_visits(station_id)")
    }

    private fun createTaskTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id TEXT PRIMARY KEY NOT NULL,
                station_id INTEGER NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('MULTIPLE_CHOICE')),
                prompt TEXT NOT NULL CHECK (length(trim(prompt)) > 0),
                position INTEGER NOT NULL CHECK (position >= 0),
                FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE,
                UNIQUE (station_id, position)
            )"""
        )
        db.execSQL("CREATE INDEX index_tasks_station_id ON tasks(station_id)")
        db.execSQL(
            """CREATE TABLE task_options (
                id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL,
                text TEXT NOT NULL CHECK (length(trim(text)) > 0),
                position INTEGER NOT NULL CHECK (position >= 0),
                is_correct INTEGER NOT NULL CHECK (is_correct IN (0, 1)),
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                UNIQUE (task_id, position)
            )"""
        )
        db.execSQL("CREATE INDEX index_task_options_task_id ON task_options(task_id)")
        db.execSQL(
            """CREATE UNIQUE INDEX index_task_options_one_correct
                ON task_options(task_id) WHERE is_correct = 1"""
        )
        db.execSQL(
            """CREATE TABLE task_attempts (
                id TEXT PRIMARY KEY NOT NULL,
                session_id INTEGER NOT NULL,
                task_id TEXT NOT NULL,
                selected_option_id TEXT NOT NULL,
                selected_option_text_snapshot TEXT NOT NULL,
                prompt_text_snapshot TEXT NOT NULL,
                correctness INTEGER CHECK (correctness IS NULL OR correctness IN (0, 1)),
                selected_at INTEGER NOT NULL,
                submitted_at INTEGER,
                FOREIGN KEY (session_id) REFERENCES game_sessions(id) ON DELETE CASCADE,
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE RESTRICT,
                CHECK (
                    (submitted_at IS NULL AND correctness IS NULL) OR
                    (submitted_at IS NOT NULL AND correctness IS NOT NULL)
                )
            )"""
        )
        db.execSQL(
            "CREATE INDEX index_task_attempts_session_task ON task_attempts(session_id, task_id, selected_at)"
        )
        db.execSQL(
            """CREATE UNIQUE INDEX index_task_attempts_one_pending
                ON task_attempts(session_id, task_id) WHERE submitted_at IS NULL"""
        )
    }

    private fun migrateV1ToV2(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE stations_v2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_id INTEGER NOT NULL,
                title TEXT NOT NULL CHECK (length(trim(title)) > 0),
                body_text TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL CHECK (position >= 0),
                qr_token TEXT NOT NULL UNIQUE,
                FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
                UNIQUE (game_id, position)
            )"""
        )
        db.rawQuery(
            "SELECT id, game_id, title, body_text, position FROM stations",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.insertOrThrow(
                    "stations_v2",
                    null,
                    ContentValues().apply {
                        put("id", cursor.getLong(0))
                        put("game_id", cursor.getLong(1))
                        put("title", cursor.getString(2))
                        put("body_text", cursor.getString(3))
                        put("position", cursor.getInt(4))
                        put("qr_token", UUID.randomUUID().toString())
                    }
                )
            }
        }
        db.execSQL("DROP TABLE stations")
        db.execSQL("ALTER TABLE stations_v2 RENAME TO stations")
        db.execSQL("CREATE INDEX index_stations_game_id ON stations(game_id)")
    }

    companion object {
        const val DATABASE_NAME = "qrhry.db"
        const val DATABASE_VERSION = 5
    }
}