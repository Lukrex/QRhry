package com.qrhry.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.MultipleChoiceTask
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.GameSession
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.TaskOption
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.TaskAttempt
import com.qrhry.app.domain.TaskProgress
import com.qrhry.app.domain.SessionProgress
import com.qrhry.app.domain.Station
import com.qrhry.app.domain.StationVisit
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.StationQrLookup
import com.qrhry.app.domain.StationMedia
import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.TaskSubmissionResult
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
            "SELECT id, title, game_uuid, content_version, updated_at FROM games ORDER BY id DESC",
            null
        ).use { gameCursor ->
            while (gameCursor.moveToNext()) {
                val gameId = gameCursor.getLong(0)
                games += Game(
                    id = gameId,
                    title = gameCursor.getString(1),
                    stations = getStations(database, gameId),
                    gameUuid = gameCursor.getString(2),
                    contentVersion = gameCursor.getInt(3),
                    updatedAt = gameCursor.getLong(4)
                )
            }
        }
        return games
    }

    fun getGame(gameId: Long): Game? = getAllGames().firstOrNull { it.id == gameId }

    fun updateStationContent(
        stationId: Long,
        title: String,
        bodyText: String,
        replacements: List<StationMedia> = emptyList(),
        tasks: List<MultipleChoiceTaskDraft>? = null
    ): List<StationMedia>? {
        require(title.isNotBlank()) { "Enter a station title." }
        tasks?.let { drafts ->
            GameDraftValidator.taskValidationError(drafts)?.let { throw IllegalArgumentException(it) }
        }
        val database = databaseHelper.writableDatabase
        val previous = mutableListOf<StationMedia>()
        database.beginTransaction()
        try {
            val gameId = database.rawQuery(
                "SELECT game_id FROM stations WHERE id = ?",
                arrayOf(stationId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
            replacements.forEach { media ->
                getStationMedia(database, stationId, media.mediaType)?.let(previous::add)
                database.delete(
                    "station_media",
                    "station_id = ? AND media_type = ?",
                    arrayOf(stationId.toString(), media.mediaType.name)
                )
                database.insertOrThrow(
                    "station_media",
                    null,
                    ContentValues().apply {
                        put("id", media.id)
                        put("station_id", stationId)
                        put("media_type", media.mediaType.name)
                        put("relative_path", media.relativePath)
                        put("mime_type", media.mimeType)
                        put("original_filename", media.originalFilename)
                        put("checksum", media.checksum)
                        put("byte_size", media.byteSize)
                        put("display_order", if (media.mediaType == StationMediaType.IMAGE) 0 else 1)
                    }
                )
            }
            database.execSQL(
                "UPDATE stations SET title = ?, body_text = ? WHERE id = ?",
                arrayOf(title.trim(), bodyText.trim(), stationId)
            )
            if (tasks != null) replaceStationTasks(database, stationId, gameId, tasks)
            incrementContentVersion(database, gameId)
            database.setTransactionSuccessful()
            return previous
        } finally {
            database.endTransaction()
        }
    }

    fun getGameUuidForStation(stationId: Long): String? =
        databaseHelper.readableDatabase.rawQuery(
            """SELECT games.game_uuid FROM games
                INNER JOIN stations ON stations.game_id = games.id WHERE stations.id = ?""",
            arrayOf(stationId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun getGameIdForStation(stationId: Long): Long? =
        databaseHelper.readableDatabase.rawQuery(
            "SELECT game_id FROM stations WHERE id = ?",
            arrayOf(stationId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun replaceStationMedia(stationId: Long, media: StationMedia): StationMedia? {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val gameId = database.rawQuery(
                "SELECT game_id FROM stations WHERE id = ?",
                arrayOf(stationId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
            val previous = getStationMedia(database, stationId, media.mediaType)
            database.delete(
                "station_media",
                "station_id = ? AND media_type = ?",
                arrayOf(stationId.toString(), media.mediaType.name)
            )
            database.insertOrThrow(
                "station_media",
                null,
                ContentValues().apply {
                    put("id", media.id)
                    put("station_id", stationId)
                    put("media_type", media.mediaType.name)
                    put("relative_path", media.relativePath)
                    put("mime_type", media.mimeType)
                    put("original_filename", media.originalFilename)
                    put("checksum", media.checksum)
                    put("byte_size", media.byteSize)
                    put("display_order", if (media.mediaType == StationMediaType.IMAGE) 0 else 1)
                }
            )
            incrementContentVersion(database, gameId)
            database.setTransactionSuccessful()
            return previous
        } finally {
            database.endTransaction()
        }
    }

    fun replaceGameStationMedia(replacements: List<Pair<Long, StationMedia>>): List<StationMedia> {
        val database = databaseHelper.writableDatabase
        val previous = mutableListOf<StationMedia>()
        database.beginTransaction()
        try {
            val changedGameIds = mutableSetOf<Long>()
            replacements.forEach { (stationId, media) ->
                val gameId = database.rawQuery(
                    "SELECT game_id FROM stations WHERE id = ?",
                    arrayOf(stationId.toString())
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "Station $stationId does not exist." }
                    cursor.getLong(0)
                }
                getStationMedia(database, stationId, media.mediaType)?.let(previous::add)
                database.delete(
                    "station_media",
                    "station_id = ? AND media_type = ?",
                    arrayOf(stationId.toString(), media.mediaType.name)
                )
                database.insertOrThrow("station_media", null, media.toContentValues(stationId))
                changedGameIds += gameId
            }
            changedGameIds.forEach { incrementContentVersion(database, it) }
            database.setTransactionSuccessful()
            return previous
        } finally {
            database.endTransaction()
        }
    }

    fun removeStationMedia(stationId: Long, mediaType: StationMediaType): StationMedia? {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val gameId = database.rawQuery(
                "SELECT game_id FROM stations WHERE id = ?",
                arrayOf(stationId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return null }
            val previous = getStationMedia(database, stationId, mediaType) ?: return null
            database.delete(
                "station_media",
                "station_id = ? AND media_type = ?",
                arrayOf(stationId.toString(), mediaType.name)
            )
            incrementContentVersion(database, gameId)
            database.setTransactionSuccessful()
            return previous
        } finally {
            database.endTransaction()
        }
    }

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
        var resumed = false
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

            val visit = getStationVisit(database, sessionId, station.id)
            if (visit?.isCompleted == true) {
                val progress = checkNotNull(getSessionProgress(sessionId))
                return StationScanResult.AlreadyVisited(station, progress)
            }

            val expectedStation = findNextStation(database, sessionId, session.gameId)
                ?: return StationScanResult.SessionCompleted
            if (station.id != expectedStation.id) {
                return StationScanResult.OutOfOrder(expectedStation, station)
            }

            val now = System.currentTimeMillis()
            if (visit == null) {
                database.insertOrThrow(
                    "station_visits",
                    null,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("station_id", station.id)
                        put("visited_at", now)
                    }
                )
            } else {
                resumed = true
            }
            acceptedStation = station
            if (getTasks(database, station.id).isEmpty()) completeStation(database, session, station.id, now)
            database.execSQL("UPDATE game_sessions SET updated_at = ? WHERE id = ?", arrayOf(now, sessionId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        val progress = checkNotNull(getSessionProgress(sessionId))
        return StationScanResult.Accepted(checkNotNull(acceptedStation), progress, resumed)
    }

    fun savePendingTaskSelection(sessionId: Long, taskId: String, optionId: String): SessionProgress {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        try {
            val session = getSession(database, sessionId)
                ?: throw IllegalArgumentException("The game session is unavailable.")
            check(session.status == GameSessionStatus.IN_PROGRESS) { "The game session is complete." }
            val stationId = getTaskStationId(database, taskId)
                ?: throw IllegalArgumentException("The task is unavailable.")
            check(isCurrentVisitedStation(database, session, stationId)) {
                "This task is not part of the current station."
            }
            check(!isTaskCompleted(database, sessionId, taskId)) { "This task is already complete." }
            val task = getTasks(database, stationId).first { it.id == taskId }
            val option = task.options.firstOrNull { it.id == optionId }
                ?: throw IllegalArgumentException("The selected answer is unavailable.")
            val now = System.currentTimeMillis()
            val pendingId = database.rawQuery(
                "SELECT id FROM task_attempts WHERE session_id = ? AND task_id = ? AND submitted_at IS NULL",
                arrayOf(sessionId.toString(), taskId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (pendingId == null) {
                database.insertOrThrow(
                    "task_attempts",
                    null,
                    ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("session_id", sessionId)
                        put("task_id", taskId)
                        put("selected_option_id", option.id)
                        put("selected_option_text_snapshot", option.text)
                        put("prompt_text_snapshot", task.prompt)
                        putNull("correctness")
                        put("selected_at", now)
                        putNull("submitted_at")
                    }
                )
            } else {
                database.execSQL(
                    """UPDATE task_attempts SET selected_option_id = ?, selected_option_text_snapshot = ?,
                        prompt_text_snapshot = ?, selected_at = ? WHERE id = ?""",
                    arrayOf(option.id, option.text, task.prompt, now, pendingId)
                )
            }
            database.execSQL("UPDATE game_sessions SET updated_at = ? WHERE id = ?", arrayOf(now, sessionId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return checkNotNull(getSessionProgress(sessionId))
    }

    fun submitTaskAttempt(sessionId: Long, taskId: String): TaskSubmissionResult {
        val database = databaseHelper.writableDatabase
        var correctness: Boolean? = null
        database.beginTransaction()
        try {
            val session = getSession(database, sessionId)
                ?: return TaskSubmissionResult.SessionUnavailable
            if (session.status != GameSessionStatus.IN_PROGRESS) return TaskSubmissionResult.SessionUnavailable
            val stationId = getTaskStationId(database, taskId)
                ?: return TaskSubmissionResult.TaskUnavailable
            if (!isCurrentVisitedStation(database, session, stationId)) return TaskSubmissionResult.TaskUnavailable
            if (isTaskCompleted(database, sessionId, taskId)) return TaskSubmissionResult.TaskAlreadyCompleted
            val pending = database.rawQuery(
                "SELECT id, selected_option_id FROM task_attempts WHERE session_id = ? AND task_id = ? AND submitted_at IS NULL",
                arrayOf(sessionId.toString(), taskId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null }
                ?: return TaskSubmissionResult.NoPendingSelection
            val task = getTasks(database, stationId).first { it.id == taskId }
            correctness = task.options.firstOrNull { it.id == pending.second }?.isCorrect == true
            val now = System.currentTimeMillis()
            database.execSQL(
                "UPDATE task_attempts SET correctness = ?, submitted_at = ? WHERE id = ? AND submitted_at IS NULL",
                arrayOf(if (correctness == true) 1 else 0, now, pending.first)
            )
            completeStation(database, session, stationId, now)
            database.execSQL("UPDATE game_sessions SET updated_at = ? WHERE id = ?", arrayOf(now, sessionId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return TaskSubmissionResult.Submitted(
            isCorrect = checkNotNull(correctness),
            progress = checkNotNull(getSessionProgress(sessionId))
        )
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
        val visits = mutableListOf<StationVisit>()
        database.rawQuery(
            "SELECT session_id, station_id, visited_at, completed_at FROM station_visits WHERE session_id = ?",
            arrayOf(session.id.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                visits += StationVisit(
                    sessionId = cursor.getLong(0),
                    stationId = cursor.getLong(1),
                    visitedAt = cursor.getLong(2),
                    completedAt = if (cursor.isNull(3)) null else cursor.getLong(3)
                )
            }
        }
        val visitedIds = visits.map { it.stationId }.toSet()
        val completedIds = visits.filter { it.isCompleted }.map { it.stationId }.toSet()
        val currentStation = stations.firstOrNull { station ->
            visits.any { it.stationId == station.id && !it.isCompleted }
        }
        return SessionProgress(
            session = session,
            gameTitle = gameTitle,
            stations = stations,
            visitedStationIds = visitedIds,
            nextStation = stations.firstOrNull { it.id !in completedIds },
            completedStationIds = completedIds,
            stationVisits = visits,
            currentStation = currentStation,
            taskProgress = currentStation?.tasks?.map { getTaskProgress(database, session.id, it) }.orEmpty()
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
                    AND station_visits.completed_at IS NOT NULL
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

    private fun completeStation(database: SQLiteDatabase, session: GameSession, stationId: Long, completedAt: Long) {
        val hasIncompleteTask = database.rawQuery(
            """SELECT 1 FROM tasks WHERE station_id = ? AND NOT EXISTS (
                SELECT 1 FROM task_attempts
                WHERE task_attempts.session_id = ? AND task_attempts.task_id = tasks.id
                    AND task_attempts.correctness = 1 AND task_attempts.submitted_at IS NOT NULL
            ) LIMIT 1""",
            arrayOf(stationId.toString(), session.id.toString())
        ).use { it.moveToFirst() }
        if (hasIncompleteTask) return
        database.execSQL(
            "UPDATE station_visits SET completed_at = ? WHERE session_id = ? AND station_id = ? AND completed_at IS NULL",
            arrayOf(completedAt, session.id, stationId)
        )
        if (findNextStation(database, session.id, session.gameId) == null) {
            completeSession(database, session.id, completedAt)
        }
    }

    private fun getSession(database: SQLiteDatabase, sessionId: Long): GameSession? =
        database.rawQuery(
            "SELECT id, game_id, status, created_at, updated_at, completed_at FROM game_sessions WHERE id = ?",
            arrayOf(sessionId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toGameSession() else null }

    private fun getStationVisit(database: SQLiteDatabase, sessionId: Long, stationId: Long): StationVisit? =
        database.rawQuery(
            "SELECT session_id, station_id, visited_at, completed_at FROM station_visits WHERE session_id = ? AND station_id = ?",
            arrayOf(sessionId.toString(), stationId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else StationVisit(
                sessionId = cursor.getLong(0),
                stationId = cursor.getLong(1),
                visitedAt = cursor.getLong(2),
                completedAt = if (cursor.isNull(3)) null else cursor.getLong(3)
            )
        }

    private fun getTaskStationId(database: SQLiteDatabase, taskId: String): Long? =
        database.rawQuery("SELECT station_id FROM tasks WHERE id = ?", arrayOf(taskId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun isCurrentVisitedStation(database: SQLiteDatabase, session: GameSession, stationId: Long): Boolean {
        val visit = getStationVisit(database, session.id, stationId) ?: return false
        if (visit.isCompleted) return false
        val gameId = database.rawQuery(
            "SELECT game_id FROM stations WHERE id = ?",
            arrayOf(stationId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return false }
        if (gameId != session.gameId) return false
        return findNextStation(database, session.id, session.gameId)?.id == stationId
    }

    private fun isTaskCompleted(database: SQLiteDatabase, sessionId: Long, taskId: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM task_attempts WHERE session_id = ? AND task_id = ? AND correctness = 1 AND submitted_at IS NOT NULL LIMIT 1",
            arrayOf(sessionId.toString(), taskId)
        ).use { it.moveToFirst() }

    private fun getTaskProgress(
        database: SQLiteDatabase,
        sessionId: Long,
        task: MultipleChoiceTask
    ): TaskProgress {
        val attempts = mutableListOf<TaskAttempt>()
        database.rawQuery(
            """SELECT id, session_id, task_id, selected_option_id, selected_option_text_snapshot,
                prompt_text_snapshot, correctness, selected_at, submitted_at
                FROM task_attempts WHERE session_id = ? AND task_id = ? ORDER BY selected_at, id""",
            arrayOf(sessionId.toString(), task.id)
        ).use { cursor ->
            while (cursor.moveToNext()) attempts += TaskAttempt(
                id = cursor.getString(0),
                sessionId = cursor.getLong(1),
                taskId = cursor.getString(2),
                selectedOptionId = cursor.getString(3),
                selectedOptionTextSnapshot = cursor.getString(4),
                promptTextSnapshot = cursor.getString(5),
                correctness = if (cursor.isNull(6)) null else cursor.getInt(6) != 0,
                selectedAt = cursor.getLong(7),
                submittedAt = if (cursor.isNull(8)) null else cursor.getLong(8)
            )
        }
        return TaskProgress(
            task = task,
            attempts = attempts,
            pendingAttempt = attempts.firstOrNull { it.isPending },
            isCompleted = attempts.any { it.correctness == true && it.submittedAt != null }
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
            val station = Station(
                id = cursor.getLong(1),
                gameId = cursor.getLong(2),
                title = cursor.getString(3),
                bodyText = cursor.getString(4),
                position = cursor.getInt(5),
                qrToken = cursor.getString(6)
            ).let { station ->
                station.copy(media = getStationMedia(database, station.id), tasks = getTasks(database, station.id))
            }
            return StationQrLookup(
                gameTitle = cursor.getString(0),
                station = station
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
                val station = cursor.toStation()
                stations += station.copy(
                    media = getStationMedia(database, station.id),
                    tasks = getTasks(database, station.id)
                )
            }
        }
        return stations
    }

    private fun getTasks(database: SQLiteDatabase, stationId: Long): List<MultipleChoiceTask> {
        val tasks = mutableListOf<MultipleChoiceTask>()
        database.rawQuery(
            "SELECT id, station_id, prompt, position FROM tasks WHERE station_id = ? ORDER BY position",
            arrayOf(stationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val taskId = cursor.getString(0)
                tasks += MultipleChoiceTask(
                    id = taskId,
                    stationId = cursor.getLong(1),
                    prompt = cursor.getString(2),
                    position = cursor.getInt(3),
                    options = getTaskOptions(database, taskId)
                )
            }
        }
        return tasks
    }

    private fun getTaskOptions(database: SQLiteDatabase, taskId: String): List<TaskOption> {
        val options = mutableListOf<TaskOption>()
        database.rawQuery(
            "SELECT id, task_id, text, position, is_correct FROM task_options WHERE task_id = ? ORDER BY position",
            arrayOf(taskId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                options += TaskOption(
                    id = cursor.getString(0),
                    taskId = cursor.getString(1),
                    text = cursor.getString(2),
                    position = cursor.getInt(3),
                    isCorrect = cursor.getInt(4) != 0
                )
            }
        }
        return options
    }

    private fun replaceStationTasks(
        database: SQLiteDatabase,
        stationId: Long,
        gameId: Long,
        drafts: List<MultipleChoiceTaskDraft>
    ) {
        val current = getTasks(database, stationId)
        if (current.matchesDrafts(drafts)) return
        val hasActiveSession = database.rawQuery(
            "SELECT 1 FROM game_sessions WHERE game_id = ? AND status = 'IN_PROGRESS' LIMIT 1",
            arrayOf(gameId.toString())
        ).use { it.moveToFirst() }
        check(!hasActiveSession) { "Tasks cannot be changed while this game has an active session." }

        val retainedIds = drafts.map { it.id }.toSet()
        current.filter { it.id !in retainedIds }.forEach { task ->
            val hasAttempts = database.rawQuery(
                "SELECT 1 FROM task_attempts WHERE task_id = ? LIMIT 1",
                arrayOf(task.id)
            ).use { it.moveToFirst() }
            check(!hasAttempts) { "A task with saved attempts cannot be removed." }
        }

        database.execSQL("UPDATE tasks SET position = position + 100000 WHERE station_id = ?", arrayOf(stationId))
        drafts.forEach { draft ->
            val existing = current.firstOrNull { it.id == draft.id }
            if (existing == null) {
                database.insertOrThrow(
                    "tasks",
                    null,
                    ContentValues().apply {
                        put("id", draft.id)
                        put("station_id", stationId)
                        put("type", "MULTIPLE_CHOICE")
                        put("prompt", draft.prompt.trim())
                        put("position", draft.position)
                    }
                )
            } else {
                database.execSQL(
                    "UPDATE tasks SET prompt = ?, position = ? WHERE id = ? AND station_id = ?",
                    arrayOf(draft.prompt.trim(), draft.position, draft.id, stationId)
                )
            }
            database.delete("task_options", "task_id = ?", arrayOf(draft.id))
            draft.options.forEach { option -> insertTaskOption(database, draft.id, option) }
        }
        current.filter { it.id !in retainedIds }.forEach { task ->
            database.delete("tasks", "id = ?", arrayOf(task.id))
        }
    }

    private fun List<MultipleChoiceTask>.matchesDrafts(drafts: List<MultipleChoiceTaskDraft>): Boolean =
        size == drafts.size && zip(drafts).all { (task, draft) ->
            task.id == draft.id && task.prompt == draft.prompt.trim() && task.position == draft.position &&
                task.options.size == draft.options.size && task.options.zip(draft.options).all { (option, item) ->
                    option.id == item.id && option.text == item.text.trim() &&
                        option.position == item.position && option.isCorrect == item.isCorrect
                }
        }

    private fun insertStationTasks(
        database: SQLiteDatabase,
        stationId: Long,
        drafts: List<MultipleChoiceTaskDraft>
    ) {
        drafts.forEach { task ->
            database.insertOrThrow(
                "tasks",
                null,
                ContentValues().apply {
                    put("id", task.id)
                    put("station_id", stationId)
                    put("type", "MULTIPLE_CHOICE")
                    put("prompt", task.prompt.trim())
                    put("position", task.position)
                }
            )
            task.options.forEach { insertTaskOption(database, task.id, it) }
        }
    }

    private fun insertTaskOption(database: SQLiteDatabase, taskId: String, option: TaskOptionDraft) {
        database.insertOrThrow(
            "task_options",
            null,
            ContentValues().apply {
                put("id", option.id)
                put("task_id", taskId)
                put("text", option.text.trim())
                put("position", option.position)
                put("is_correct", if (option.isCorrect) 1 else 0)
            }
        )
    }

    private fun getStationMedia(database: SQLiteDatabase, stationId: Long): List<StationMedia> {
        val items = mutableListOf<StationMedia>()
        database.rawQuery(
            """SELECT id, station_id, media_type, relative_path, mime_type,
                original_filename, checksum, byte_size, display_order
                FROM station_media WHERE station_id = ? ORDER BY display_order""",
            arrayOf(stationId.toString())
        ).use { cursor -> while (cursor.moveToNext()) items += cursor.toStationMedia() }
        return items
    }

    private fun getStationMedia(
        database: SQLiteDatabase,
        stationId: Long,
        mediaType: StationMediaType
    ): StationMedia? = database.rawQuery(
        """SELECT id, station_id, media_type, relative_path, mime_type,
            original_filename, checksum, byte_size, display_order
            FROM station_media WHERE station_id = ? AND media_type = ?""",
        arrayOf(stationId.toString(), mediaType.name)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toStationMedia() else null }

    private fun incrementContentVersion(database: SQLiteDatabase, gameId: Long) {
        database.execSQL(
            "UPDATE games SET content_version = content_version + 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), gameId)
        )
    }

    private fun StationMedia.toContentValues(stationId: Long) = ContentValues().apply {
        put("id", id)
        put("station_id", stationId)
        put("media_type", mediaType.name)
        put("relative_path", relativePath)
        put("mime_type", mimeType)
        put("original_filename", originalFilename)
        put("checksum", checksum)
        put("byte_size", byteSize)
        put("display_order", if (mediaType == StationMediaType.IMAGE) 0 else 1)
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

    private fun android.database.Cursor.toStationMedia() = StationMedia(
        id = getString(0),
        stationId = getLong(1),
        mediaType = StationMediaType.valueOf(getString(2)),
        relativePath = getString(3),
        mimeType = getString(4),
        originalFilename = if (isNull(5)) null else getString(5),
        checksum = getString(6),
        byteSize = getLong(7),
        displayOrder = getInt(8)
    )

    private fun SQLiteDatabase.beginTransactionAndInsertGame(draft: GameDraft): Long {
        beginTransaction()
        try {
            val gameId = insertOrThrow(
                "games",
                null,
                ContentValues().apply {
                    put("title", draft.title.trim())
                    put("game_uuid", UUID.randomUUID().toString())
                    put("updated_at", System.currentTimeMillis())
                }
            )
            draft.stations.forEachIndexed { index, station ->
                val stationId = insertOrThrow(
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
                insertStationTasks(this, stationId, station.tasks)
            }
            setTransactionSuccessful()
            return gameId
        } finally {
            endTransaction()
        }
    }
}