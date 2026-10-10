package com.qrhry.app.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.TaskSubmissionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameTaskRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var databaseHelper: GameDatabaseHelper
    private lateinit var repository: GameRepository

    @Before
    fun setUp() {
        databaseName = "qrhry-task-test-${UUID.randomUUID()}.db"
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
    }

    @After
    fun tearDown() {
        databaseHelper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun createsAndLoadsOrderedTasksAndOptions() {
        val firstTask = task("First question", position = 0, optionTexts = listOf("A", "B", "C"))
        val secondTask = task("Second question", position = 1)
        val game = createGame(listOf(firstTask, secondTask))
        val loaded = checkNotNull(repository.getGame(game.id)).stations.first().tasks

        assertEquals(listOf(firstTask.id, secondTask.id), loaded.map { it.id })
        assertEquals(listOf("A", "B", "C"), loaded.first().options.map { it.text })
        assertEquals(listOf(0, 1, 2), loaded.first().options.map { it.position })
        assertEquals(1, loaded.first().options.count { it.isCorrect })
        assertEquals(100, loaded.first().points)
    }

    @Test
    fun editingReordersAndRemovesOptionsWithoutChangingTaskIdentity() {
        val original = task("Question", optionTexts = listOf("A", "B", "C"))
        val game = createGame(listOf(original))
        val station = game.stations.first()
        val updatedOptions = listOf(
            original.options[2].copy(position = 0),
            original.options[0].copy(position = 1)
        )
        val updatedTask = original.copy(options = updatedOptions, points = 275)

        repository.updateStationContent(
            stationId = station.id,
            title = station.title,
            bodyText = station.bodyText,
            tasks = listOf(updatedTask)
        )

        val loadedTask = checkNotNull(repository.getGame(game.id)).stations.first().tasks.single()
        assertEquals(original.id, loadedTask.id)
        assertEquals(listOf(original.options[2].id, original.options[0].id), loadedTask.options.map { it.id })
        assertEquals(listOf("C", "A"), loadedTask.options.map { it.text })
        assertEquals(listOf(0, 1), loadedTask.options.map { it.position })
        assertEquals(275, loadedTask.points)

        repository.updateStationContent(
            stationId = station.id,
            title = station.title,
            bodyText = station.bodyText,
            tasks = emptyList()
        )
        assertTrue(checkNotNull(repository.getGame(game.id)).stations.first().tasks.isEmpty())
    }

    @Test
    fun invalidTaskDefinitionsAreRejectedBeforePersistence() {
        val noCorrectAnswer = task("Question").copy(
            options = task("Other").options.map { it.copy(isCorrect = false) }
        )
        val multipleCorrectAnswers = task("Question").copy(
            options = task("Other").options.map { it.copy(isCorrect = true) }
        )
        val oneOption = task("Question").copy(options = task("Other").options.take(1))
        val blankOption = task("Question").copy(
            options = task("Other").options.mapIndexed { index, option ->
                if (index == 0) option.copy(text = " ") else option
            }
        )
        val invalidOptionOrder = task("Question").copy(
            options = task("Other").options.mapIndexed { index, option -> option.copy(position = index + 1) }
        )
        val blankPrompt = task(" ")
        val negativePoints = task("Negative points").copy(points = -1)
        listOf(noCorrectAnswer, multipleCorrectAnswers, oneOption, blankOption, invalidOptionOrder, blankPrompt, negativePoints).forEach { invalid ->
            val exception = runCatching { createGame(listOf(invalid)) }.exceptionOrNull()
            assertNotNull("Invalid task was accepted: $invalid", exception)
        }
        assertTrue(repository.getAllGames().isEmpty())
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseRejectsTwoCorrectOptionsForOneTask() {
        val task = task("Question")
        val game = createGame(listOf(task))
        val options = game.stations.first().tasks.single().options
        databaseHelper.writableDatabase.execSQL(
            "UPDATE task_options SET is_correct = 1 WHERE id = ?",
            arrayOf(options.first { !it.isCorrect }.id)
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseRejectsNegativeTaskPoints() {
        val task = task("Question")
        createGame(listOf(task))
        databaseHelper.writableDatabase.execSQL(
            "UPDATE tasks SET points = -1 WHERE id = ?",
            arrayOf(task.id)
        )
    }

    @Test
    fun tasklessStationCompletesImmediatelyAndDuplicateScanDoesNotDuplicateVisit() {
        val game = createGame()
        val session = repository.startOrResumeSession(game.id)
        val accepted = repository.recordStationScan(session.session.id, game.stations.first().qrToken)
            as StationScanResult.Accepted

        assertTrue(game.stations.first().id in accepted.progress.visitedStationIds)
        assertTrue(game.stations.first().id in accepted.progress.completedStationIds)
        assertNull(accepted.progress.currentStation)
        assertEquals("Second", accepted.progress.nextStation?.title)
        val duplicate = repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        assertTrue(duplicate is StationScanResult.AlreadyVisited)
        assertEquals(1, repository.getSessionProgress(session.session.id)?.stationVisits?.count {
            it.stationId == game.stations.first().id
        })
    }

    @Test
    fun taskScanCreatesIncompleteVisitAndRescanResumesIt() {
        val game = createGame(listOf(task("Question")))
        val session = repository.startOrResumeSession(game.id)
        val first = repository.recordStationScan(session.session.id, game.stations.first().qrToken)
            as StationScanResult.Accepted

        assertTrue(game.stations.first().id in first.progress.visitedStationIds)
        assertFalse(game.stations.first().id in first.progress.completedStationIds)
        assertEquals(game.stations.first().id, first.progress.currentStation?.id)
        assertEquals("First", first.progress.nextStation?.title)
        assertNull(first.progress.stationVisits.single().completedAt)

        val resumed = repository.recordStationScan(session.session.id, game.stations.first().qrToken)
            as StationScanResult.Accepted
        assertTrue(resumed.resumed)
        assertEquals(1, resumed.progress.stationVisits.size)
    }

    @Test
    fun pendingSelectionAndUnfinishedStationRestoreAfterDatabaseReopen() {
        val game = createGame(listOf(task("Question")))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        val task = game.stations.first().tasks.single()
        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first().id)
        databaseHelper.close()

        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val restored = checkNotNull(repository.getSessionProgress(session.session.id))
        val taskProgress = restored.taskProgress.single()

        assertEquals(0L, restored.score)
        assertEquals(game.stations.first().id, restored.currentStation?.id)
        assertEquals(task.options.first().id, taskProgress.pendingAttempt?.selectedOptionId)
        assertEquals(task.options.first().text, taskProgress.pendingAttempt?.selectedOptionTextSnapshot)
        assertEquals(task.prompt, taskProgress.pendingAttempt?.promptTextSnapshot)
        assertNull(taskProgress.pendingAttempt?.submittedAt)
        assertEquals("First", restored.nextStation?.title)
    }

    @Test
    fun incorrectAttemptCanBeRetriedAndCorrectAnswerAdvances() {
        val task = task("Question").copy(points = 450)
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { !it.isCorrect }.id)
        val incorrect = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
        assertFalse(incorrect.isCorrect)
        assertEquals(0, incorrect.pointsAwarded)
        assertEquals(0L, incorrect.progress.score)
        assertTrue(game.stations.first().id !in incorrect.progress.completedStationIds)
        assertEquals("First", incorrect.progress.nextStation?.title)
        assertEquals(listOf(false), incorrect.progress.taskProgress.single().attempts.map { it.correctness })

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { it.isCorrect }.id)
        val correct = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
        assertTrue(correct.isCorrect)
        assertEquals(450, correct.pointsAwarded)
        assertEquals(450L, correct.progress.score)
        assertEquals(1, correct.progress.completedTaskCount)
        assertTrue(game.stations.first().id in correct.progress.completedStationIds)
        assertEquals("Second", correct.progress.nextStation?.title)
        val persistedCorrectness = databaseHelper.readableDatabase.rawQuery(
            "SELECT correctness FROM task_attempts WHERE session_id = ? AND task_id = ? ORDER BY submitted_at",
            arrayOf(session.session.id.toString(), task.id)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(0) != 0)
            }
        }
        assertEquals(listOf(false, true), persistedCorrectness)

        assertTrue(repository.submitTaskAttempt(session.session.id, task.id) is TaskSubmissionResult.TaskAlreadyCompleted)
        val rescan = repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        assertTrue(rescan is StationScanResult.AlreadyVisited)
        assertEquals(450L, repository.getSessionProgress(session.session.id)?.score)
    }

    @Test
    fun stationCompletesOnlyAfterEveryTaskAndFinalStationCompletesSession() {
        val tasks = listOf(task("First task", position = 0), task("Second task", position = 1))
        val game = createGame(tasks)
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)

        tasks.forEachIndexed { index, task ->
            repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { it.isCorrect }.id)
            val result = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
            if (index == 0) {
                assertTrue(game.stations.first().id !in result.progress.completedStationIds)
                assertEquals(game.stations.first().id, result.progress.currentStation?.id)
                assertTrue(result.progress.taskProgress.first().isCompleted)
            } else {
                assertTrue(game.stations.first().id in result.progress.completedStationIds)
                assertEquals("Second", result.progress.nextStation?.title)
            }
        }

        val last = repository.recordStationScan(session.session.id, game.stations[1].qrToken)
            as StationScanResult.Accepted
        assertEquals(GameSessionStatus.COMPLETED, last.progress.session.status)
        assertNull(last.progress.nextStation)
        val completed = repository.getSessionProgress(session.session.id)
        assertEquals(GameSessionStatus.COMPLETED, completed?.session?.status)
        assertNotNull(completed?.session?.completedAt)
        assertEquals(2, completed?.resultTotalTaskCount)
        assertEquals(2, completed?.resultTotalStationCount)
        assertEquals(2, completed?.completedTaskCount)
        assertEquals(200L, completed?.score)
    }

    @Test
    fun scoreAndResultsSurviveReopenTaskEditsAndNewRunsStartAtZero() {
        val task = task("Question").copy(points = 320)
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskSelection(
            session.session.id,
            task.id,
            task.options.first { it.isCorrect }.id
        )
        repository.submitTaskAttempt(session.session.id, task.id)
        repository.recordStationScan(session.session.id, game.stations[1].qrToken)

        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val reopened = checkNotNull(repository.getSessionProgress(session.session.id))
        assertEquals(GameSessionStatus.COMPLETED, reopened.session.status)
        assertEquals(320L, reopened.score)
        assertEquals(1, reopened.completedTaskCount)
        assertEquals(1, reopened.resultTotalTaskCount)
        assertEquals(2, reopened.resultTotalStationCount)

        repository.updateStationContent(
            stationId = game.stations.first().id,
            title = game.stations.first().title,
            bodyText = game.stations.first().bodyText,
            tasks = listOf(task.copy(points = 999))
        )
        assertEquals(320L, repository.getSessionProgress(session.session.id)?.score)
        assertEquals(320L, repository.getCompletedSessionResults().single().score)

        val newRun = repository.startOrResumeSession(game.id)
        assertEquals(0L, newRun.score)
        assertEquals(1, newRun.resultTotalTaskCount)
        assertEquals(2, newRun.resultTotalStationCount)
    }

    @Test
    fun selectingAnotherOptionUpdatesSinglePendingAttempt() {
        val task = task("Question")
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskSelection(session.session.id, task.id, task.options[0].id)
        repository.savePendingTaskSelection(session.session.id, task.id, task.options[1].id)

        val attempts = checkNotNull(repository.getSessionProgress(session.session.id))
            .taskProgress.single().attempts
        assertEquals(1, attempts.size)
        assertTrue(attempts.single().isPending)
        assertEquals(task.options[1].id, attempts.single().selectedOptionId)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseAllowsOnlyOnePendingAttemptPerSessionAndTask() {
        val task = task("Question")
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first().id)
        databaseHelper.writableDatabase.execSQL(
            """INSERT INTO task_attempts(
                id, session_id, task_id, selected_option_id, selected_option_text_snapshot,
                prompt_text_snapshot, correctness, selected_at, submitted_at
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL)""",
            arrayOf(
                UUID.randomUUID().toString(), session.session.id, task.id,
                task.options.last().id, task.options.last().text, task.prompt, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun taskConfigurationCannotChangeDuringActiveSession() {
        val task = task("Original prompt")
        val game = createGame(listOf(task))
        val station = game.stations.first()
        repository.startOrResumeSession(game.id)
        val changedTask = task.copy(prompt = "Changed prompt")

        val result = runCatching {
            repository.updateStationContent(
                stationId = station.id,
                title = "Changed title",
                bodyText = station.bodyText,
                tasks = listOf(changedTask)
            )
        }

        assertTrue(result.isFailure)
        val persistedStation = checkNotNull(repository.getGame(game.id)).stations.first()
        assertEquals(station.title, persistedStation.title)
        assertEquals(task.prompt, persistedStation.tasks.single().prompt)
    }

    @Test
    fun completedSessionOptionRemovalPreservesHistoricalAttemptSnapshots() {
        val task = task("Original question")
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        val correctOption = task.options.first { it.isCorrect }
        repository.savePendingTaskSelection(session.session.id, task.id, correctOption.id)
        repository.submitTaskAttempt(session.session.id, task.id)
        repository.recordStationScan(session.session.id, game.stations[1].qrToken)
        assertEquals(GameSessionStatus.COMPLETED, repository.getSessionProgress(session.session.id)?.session?.status)

        val changedTask = task.copy(
            prompt = "Updated question",
            options = listOf(
                TaskOptionDraft(UUID.randomUUID().toString(), "New A", 0, false),
                TaskOptionDraft(UUID.randomUUID().toString(), "New B", 1, true)
            )
        )
        repository.updateStationContent(
            stationId = game.stations.first().id,
            title = game.stations.first().title,
            bodyText = game.stations.first().bodyText,
            tasks = listOf(changedTask)
        )

        val snapshot = databaseHelper.readableDatabase.rawQuery(
            "SELECT selected_option_id, selected_option_text_snapshot, prompt_text_snapshot, correctness FROM task_attempts WHERE session_id = ? AND task_id = ?",
            arrayOf(session.session.id.toString(), task.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3).toString())
        }
        assertEquals(listOf(correctOption.id, correctOption.text, task.prompt, "1"), snapshot)
    }

    @Test
    fun v5MigrationPreservesAttemptsAndDoesNotInventHistoricalPoints() {
        val oldDatabase = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        createV5Schema(oldDatabase)
        oldDatabase.execSQL(
            "INSERT INTO games(id, title, game_uuid, content_version, updated_at) VALUES (1, 'Legacy', 'game-uuid', 3, 99)"
        )
        oldDatabase.execSQL(
            """INSERT INTO stations(id, game_id, title, body_text, position, qr_token)
                VALUES (11, 1, 'First', 'Text', 0, 'first-qr'), (12, 1, 'Second', 'Text', 1, 'second-qr')"""
        )
        oldDatabase.execSQL(
            """INSERT INTO game_sessions(id, game_id, status, created_at, updated_at, completed_at)
                VALUES (21, 1, 'IN_PROGRESS', 100, 110, NULL), (22, 1, 'COMPLETED', 200, 300, 300)"""
        )
        oldDatabase.execSQL(
            """INSERT INTO station_visits(session_id, station_id, visited_at, completed_at)
                VALUES (21, 11, 105, NULL), (22, 11, 205, 205), (22, 12, 220, 220)"""
        )
        oldDatabase.execSQL(
            "INSERT INTO station_media(id, station_id, media_type, relative_path, mime_type, original_filename, checksum, byte_size, display_order) VALUES ('media-id', 11, 'IMAGE', 'assets/old.png', 'image/png', 'old.png', 'hash', 42, 0)"
        )
        oldDatabase.execSQL(
            "INSERT INTO tasks(id, station_id, type, prompt, position) VALUES ('legacy-task', 11, 'MULTIPLE_CHOICE', 'Legacy prompt', 0)"
        )
        oldDatabase.execSQL(
            """INSERT INTO task_options(id, task_id, text, position, is_correct)
                VALUES ('wrong-option', 'legacy-task', 'Wrong', 0, 0), ('correct-option', 'legacy-task', 'Correct', 1, 1)"""
        )
        oldDatabase.execSQL(
            """INSERT INTO task_attempts(
                id, session_id, task_id, selected_option_id, selected_option_text_snapshot,
                prompt_text_snapshot, correctness, selected_at, submitted_at
            ) VALUES
                ('wrong-attempt', 21, 'legacy-task', 'wrong-option', 'Wrong', 'Legacy prompt', 0, 106, 107),
                ('pending-attempt', 21, 'legacy-task', 'correct-option', 'Correct', 'Legacy prompt', NULL, 108, NULL),
                ('correct-attempt', 22, 'legacy-task', 'correct-option', 'Correct', 'Legacy prompt', 1, 250, 251)"""
        )
        oldDatabase.execSQL("PRAGMA user_version = 5")
        oldDatabase.close()
        databaseHelper.close()

        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val game = checkNotNull(repository.getGame(1))
        val active = checkNotNull(repository.getSessionProgress(21))
        val historical = checkNotNull(repository.getSessionProgress(22))
        val historyRow = repository.getCompletedSessionResults().single()

        assertEquals(100, game.stations.first().tasks.single().points)
        assertEquals("Legacy prompt", game.stations.first().tasks.single().prompt)
        assertEquals(listOf("Wrong", "Correct"), game.stations.first().tasks.single().options.map { it.text })
        assertEquals("media-id", game.stations.first().media.single().id)
        assertEquals(1, active.resultTotalTaskCount)
        assertEquals(2, active.resultTotalStationCount)
        assertEquals(0L, active.score)
        assertEquals(listOf(false, null), active.taskProgress.single().attempts.map { it.correctness })
        assertEquals(listOf(0, 0), active.taskProgress.single().attempts.map { it.awardedPoints })
        assertEquals("Wrong", active.taskProgress.single().attempts.first().selectedOptionTextSnapshot)
        assertEquals("Legacy prompt", active.taskProgress.single().attempts.first().promptTextSnapshot)
        assertEquals(GameSessionStatus.COMPLETED, historical.session.status)
        assertEquals(0L, historical.score)
        assertEquals(1, historical.completedTaskCount)
        assertNull(historical.resultTotalTaskCount)
        assertNull(historical.resultTotalStationCount)
        assertEquals(0L, historyRow.score)
        assertEquals(1, historyRow.completedTaskCount)
        assertNull(historyRow.totalTaskCount)
        assertNull(historyRow.totalStationCount)
        assertEquals(3, databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM task_attempts",
            null
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
    }

    @Test
    fun taskFromAnotherGameCannotBeSelectedInCurrentSession() {
        val firstGame = createGame()
        val otherTask = task("Other game task")
        val otherGame = createGame(listOf(otherTask))
        val session = repository.startOrResumeSession(firstGame.id)
        repository.recordStationScan(session.session.id, firstGame.stations.first().qrToken)

        val result = runCatching {
            repository.savePendingTaskSelection(session.session.id, otherTask.id, otherTask.options.first().id)
        }
        assertTrue(result.isFailure)
        assertEquals(0, databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM task_attempts WHERE session_id = ?",
            arrayOf(session.session.id.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertNotNull(otherGame)
    }

    private fun createGame(tasks: List<MultipleChoiceTaskDraft> = emptyList()) = repository.createGame(
        GameDraft(
            title = "Task game",
            stations = listOf(
                StationDraft("First", "First text", tasks),
                StationDraft("Second", "Second text")
            )
        )
    )

    private fun task(
        prompt: String,
        position: Int = 0,
        optionTexts: List<String> = listOf("Wrong", "Correct")
    ) = MultipleChoiceTaskDraft(
        id = UUID.randomUUID().toString(),
        prompt = prompt,
        position = position,
        options = optionTexts.mapIndexed { index, text ->
            TaskOptionDraft(
                id = UUID.randomUUID().toString(),
                text = text,
                position = index,
                isCorrect = index == optionTexts.lastIndex
            )
        }
    )

    private fun createV5Schema(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE games (id INTEGER PRIMARY KEY, title TEXT NOT NULL, game_uuid TEXT NOT NULL, content_version INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE stations (id INTEGER PRIMARY KEY, game_id INTEGER NOT NULL, title TEXT NOT NULL, body_text TEXT NOT NULL, position INTEGER NOT NULL, qr_token TEXT NOT NULL)")
        database.execSQL("CREATE TABLE game_sessions (id INTEGER PRIMARY KEY, game_id INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER)")
        database.execSQL("CREATE TABLE station_visits (session_id INTEGER NOT NULL, station_id INTEGER NOT NULL, visited_at INTEGER NOT NULL, completed_at INTEGER, PRIMARY KEY(session_id, station_id))")
        database.execSQL("CREATE TABLE station_media (id TEXT PRIMARY KEY, station_id INTEGER NOT NULL, media_type TEXT NOT NULL, relative_path TEXT NOT NULL, mime_type TEXT NOT NULL, original_filename TEXT, checksum TEXT NOT NULL, byte_size INTEGER NOT NULL, display_order INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, station_id INTEGER NOT NULL, type TEXT NOT NULL, prompt TEXT NOT NULL, position INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE task_options (id TEXT PRIMARY KEY, task_id TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, is_correct INTEGER NOT NULL)")
        database.execSQL("""CREATE TABLE task_attempts (
            id TEXT PRIMARY KEY, session_id INTEGER NOT NULL, task_id TEXT NOT NULL,
            selected_option_id TEXT NOT NULL, selected_option_text_snapshot TEXT NOT NULL,
            prompt_text_snapshot TEXT NOT NULL, correctness INTEGER, selected_at INTEGER NOT NULL,
            submitted_at INTEGER
        )""")
    }
}