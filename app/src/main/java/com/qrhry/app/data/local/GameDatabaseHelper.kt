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
                title TEXT NOT NULL CHECK (length(trim(title)) > 0)
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
        createSessionTables(db)
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
                else -> throw SQLiteException(
                    "No migration is defined from database version $version to $newVersion."
                )
            }
        }
        if (version != newVersion) throw SQLiteException("Unsupported database version $newVersion.")
    }

    private fun migrateV2ToV3(db: SQLiteDatabase) {
        createSessionTables(db)
    }

    private fun createSessionTables(db: SQLiteDatabase) {
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
                PRIMARY KEY (session_id, station_id),
                FOREIGN KEY (session_id) REFERENCES game_sessions(id) ON DELETE CASCADE,
                FOREIGN KEY (station_id) REFERENCES stations(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX index_station_visits_station_id ON station_visits(station_id)")
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
        const val DATABASE_VERSION = 3
    }
}