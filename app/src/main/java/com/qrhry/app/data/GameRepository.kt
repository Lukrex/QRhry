package com.qrhry.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.Station
import com.qrhry.app.domain.StationQrLookup
import java.util.UUID

class GameRepository(private val databaseHelper: GameDatabaseHelper) {
    fun createGame(draft: GameDraft): Game {
        GameDraftValidator.validationError(draft)?.let { throw IllegalArgumentException(it) }

        val database = databaseHelper.writableDatabase
        val gameId = database.beginTransactionAndInsertGame(draft)
        return getGame(gameId) ?: error("Created game $gameId could not be loaded.")
    }

    fun getAllGames(): List<Game> {
        val games = mutableListOf<Game>()
        val database = databaseHelper.readableDatabase
        database.rawQuery(
            "SELECT id, title FROM games ORDER BY id DESC",
            null
        ).use { gameCursor ->
            while (gameCursor.moveToNext()) {
                val gameId = gameCursor.getLong(0)
                games += Game(
                    id = gameId,
                    title = gameCursor.getString(1),
                    stations = getStations(database, gameId)
                )
            }
        }
        return games
    }

    fun getGame(gameId: Long): Game? = getAllGames().firstOrNull { it.id == gameId }

    fun findStationByQrToken(qrToken: String): StationQrLookup? {
        val database = databaseHelper.readableDatabase
        database.rawQuery(
            """SELECT games.title, stations.id, stations.game_id, stations.title,
                stations.body_text, stations.position, stations.qr_token
                FROM stations INNER JOIN games ON games.id = stations.game_id
                WHERE stations.qr_token = ?""",
            arrayOf(qrToken)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StationQrLookup(
                gameTitle = cursor.getString(0),
                station = Station(
                    id = cursor.getLong(1),
                    gameId = cursor.getLong(2),
                    title = cursor.getString(3),
                    bodyText = cursor.getString(4),
                    position = cursor.getInt(5),
                    qrToken = cursor.getString(6)
                )
            )
        }
    }

    private fun getStations(database: SQLiteDatabase, gameId: Long): List<Station> {
        val stations = mutableListOf<Station>()
        database.rawQuery(
            """SELECT id, game_id, title, body_text, position, qr_token
                FROM stations WHERE game_id = ? ORDER BY position ASC""",
            arrayOf(gameId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                stations += Station(
                    id = cursor.getLong(0),
                    gameId = cursor.getLong(1),
                    title = cursor.getString(2),
                    bodyText = cursor.getString(3),
                    position = cursor.getInt(4),
                    qrToken = cursor.getString(5)
                )
            }
        }
        return stations
    }

    private fun SQLiteDatabase.beginTransactionAndInsertGame(draft: GameDraft): Long {
        beginTransaction()
        try {
            val gameId = insertOrThrow(
                "games",
                null,
                ContentValues().apply { put("title", draft.title.trim()) }
            )
            draft.stations.forEachIndexed { index, station ->
                insertOrThrow(
                    "stations",
                    null,
                    ContentValues().apply {
                        put("game_id", gameId)
                        put("title", station.title.trim())
                        put("body_text", station.bodyText.trim())
                        put("position", index)
                        put("qr_token", UUID.randomUUID().toString())
                    }
                )
            }
            setTransactionSuccessful()
            return gameId
        } finally {
            endTransaction()
        }
    }
}