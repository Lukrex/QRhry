package com.qrhry.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.GameSession
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.SessionProgress
import com.qrhry.app.domain.Station
import com.qrhry.app.domain.StationScanResult
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
        return findStationByQrToken(database, qrToken)
    }

    fun getActiveSessions(): List<GameSession> {
        val sessions = mutableListOf<GameSession>()
        databaseHelper.readableDatabase.rawQuery(
            """SELECT id, game_id, status, created_at, updated_at, completed_at
                FROM game_sessions WHERE status = 'IN_PROGRESS' ORDER BY updated_at DESC""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) sessions += cursor.toGameSession()
        }
        return sessions
    }

    fun getActiveSession(gameId: Long): GameSession? =
        databaseHelper.readableDatabase.rawQuery(
            """SELECT id, game_id, status, created_at, updated_at, completed_at
                FROM game_sessions WHERE game_id = ? AND status = 'IN_PROGRESS'""",
            arrayOf(gameId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toGameSession() else null }

    fun startOrResumeSession(gameId: Long): SessionProgress {
        val database = databaseHelper.writableDatabase
        var sessionId: Long? = null
        database.beginTransaction()
        try {
            database.rawQuery(
                "SELECT id FROM game_sessions WHERE game_id = ? AND status = 'IN_PROGRESS'",
                arrayOf(gameId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) sessionId = cursor.getLong(0) }

            if (sessionId == null) {
                val hasGame = database.rawQuery(
                    "SELECT 1 FROM games WHERE id = ?",
                    arrayOf(gameId.toString())
                ).use { it.moveToFirst() }
                require(hasGame) { "The selected game does not exist." }

                val hasStations = database.rawQuery(
                    "SELECT 1 FROM stations WHERE game_id = ? LIMIT 1",
                    arrayOf(gameId.toString())
                ).use { it.moveToFirst() }
                require(hasStations) { "The selected game has no stations." }

                val now = System.currentTimeMillis()
                sessionId = database.insertOrThrow(
                    "game_sessions",
                    null,
                    ContentValues().apply {
                        put("game_id", gameId)
                        put("status", GameSessionStatus.IN_PROGRESS.name)
                        put("created_at", now)
                        put("updated_at", now)
                    }
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return checkNotNull(getSessionProgress(checkNotNull(sessionId)))
    }

    fun getSessionProgress(sessionId: Long): SessionProgress? {
        val database = databaseHelper.readableDatabase
        val session: GameSession
        val gameTitle: String
        database.rawQuery(
            """SELECT game_sessions.id, game_sessions.game_id, game_sessions.status,
                game_sessions.created_at, game_sessions.updated_at, game_sessions.completed_at,
                games.title
                FROM game_sessions INNER JOIN games ON games.id = game_sessions.game_id
                WHERE game_sessions.id = ?""",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            session = cursor.toGameSession()
            gameTitle = cursor.getString(6)
        }
        return loadSessionProgress(database, session, gameTitle)
    }

    fun recordStationScan(sessionId: Long, qrToken: String): StationScanResult {
        val database = databaseHelper.writableDatabase
        var acceptedStation: Station? = null
        database.beginTransaction()
        try {
            val session = database.rawQuery(
                """SELECT id, game_id, status, created_at, updated_at, completed_at
                    FROM game_sessions WHERE id = ?""",
                arrayOf(sessionId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toGameSession() else null }
                ?: return StationScanResult.SessionUnavailable

            if (session.status == GameSessionStatus.COMPLETED) {
                return StationScanResult.SessionCompleted
            }

            val lookup = findStationByQrToken(database, qrToken)
                ?: return StationScanResult.UnknownQr
            val station = lookup.station
            if (station.gameId != session.gameId) return StationScanResult.WrongGame(station)

            val alreadyVisited = database.rawQuery(
                "SELECT 1 FROM station_visits WHERE session_id = ? AND station_id = ?",
                arrayOf(sessionId.toString(), station.id.toString())
            ).use { it.moveToFirst() }
            if (alreadyVisited) {
                val progress = checkNotNull(getSessionProgress(sessionId))
                return StationScanResult.AlreadyVisited(station, progress)
            }

            val expectedStation = findNextStation(database, sessionId, session.gameId)
                ?: return StationScanResult.SessionCompleted
            if (station.id != expectedStation.id) {
                return StationScanResult.OutOfOrder(expectedStation, station)
            }

            val now = System.currentTimeMillis()
            database.insertOrThrow(
                "station_visits",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("station_id", station.id)
                    put("visited_at", now)
                }
            )
            acceptedStation = station
            val remaining = findNextStation(database, sessionId, session.gameId)
            if (remaining == null) {
                completeSession(database, sessionId, now)
            } else {
                database.execSQL(
                    "UPDATE game_sessions SET updated_at = ? WHERE id = ?",
                    arrayOf(now, sessionId)
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        val progress = checkNotNull(getSessionProgress(sessionId))
        return StationScanResult.Accepted(checkNotNull(acceptedStation), progress)
    }

    fun completeSession(sessionId: Long): Boolean {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val gameId = database.rawQuery(
                "SELECT game_id FROM game_sessions WHERE id = ? AND status = 'IN_PROGRESS'",
                arrayOf(sessionId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return false }
            if (findNextStation(database, sessionId, gameId) != null) return false
            completeSession(database, sessionId, System.currentTimeMillis())
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    private fun loadSessionProgress(
        database: SQLiteDatabase,
        session: GameSession,
        gameTitle: String
    ): SessionProgress {
        val stations = getStations(database, session.gameId)
        val visitedIds = mutableSetOf<Long>()
        database.rawQuery(
            "SELECT station_id FROM station_visits WHERE session_id = ?",
            arrayOf(session.id.toString())
        ).use { cursor -> while (cursor.moveToNext()) visitedIds += cursor.getLong(0) }
        return SessionProgress(
            session = session,
            gameTitle = gameTitle,
            stations = stations,
            visitedStationIds = visitedIds,
            nextStation = stations.firstOrNull { it.id !in visitedIds }
        )
    }

    private fun findNextStation(
        database: SQLiteDatabase,
        sessionId: Long,
        gameId: Long
    ): Station? = database.rawQuery(
        """SELECT stations.id, stations.game_id, stations.title, stations.body_text,
            stations.position, stations.qr_token
            FROM stations WHERE stations.game_id = ?
            AND NOT EXISTS (
                SELECT 1 FROM station_visits
                WHERE station_visits.session_id = ? AND station_visits.station_id = stations.id
            ) ORDER BY stations.position ASC LIMIT 1""",
        arrayOf(gameId.toString(), sessionId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toStation() else null }

    private fun completeSession(database: SQLiteDatabase, sessionId: Long, completedAt: Long) {
        database.execSQL(
            """UPDATE game_sessions SET status = 'COMPLETED', updated_at = ?, completed_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS'""",
            arrayOf(completedAt, completedAt, sessionId)
        )
    }

    private fun findStationByQrToken(database: SQLiteDatabase, qrToken: String): StationQrLookup? {
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
                stations += cursor.toStation()
            }
        }
        return stations
    }

    private fun android.database.Cursor.toStation() = Station(
        id = getLong(0),
        gameId = getLong(1),
        title = getString(2),
        bodyText = getString(3),
        position = getInt(4),
        qrToken = getString(5)
    )

    private fun android.database.Cursor.toGameSession() = GameSession(
        id = getLong(0),
        gameId = getLong(1),
        status = GameSessionStatus.valueOf(getString(2)),
        createdAt = getLong(3),
        updatedAt = getLong(4),
        completedAt = if (isNull(5)) null else getLong(5)
    )

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