package com.qrhry.app.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameProgressionMode
import com.qrhry.app.domain.GameRouteDraft
import com.qrhry.app.domain.RouteCondition
import com.qrhry.app.domain.RouteTransitionDraft
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.MultipleChoiceTask
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.StationTaskDraft
import com.qrhry.app.domain.TrueFalseTaskDraft
import com.qrhry.app.domain.ShortTextTaskDraft
import com.qrhry.app.domain.TaskAnswer
import com.qrhry.app.domain.TrueFalseTask
import com.qrhry.app.domain.ShortTextTask
import com.qrhry.app.domain.TaskScoringMode
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
        assertEquals(TaskScoringMode.EVENTUAL_CORRECT, game.scoringMode)
        val loaded = checkNotNull(repository.getGame(game.id)).stations.first().tasks

        assertEquals(listOf(firstTask.id, secondTask.id), loaded.map { it.id })
        assertEquals(listOf("A", "B", "C"), (loaded.first() as MultipleChoiceTask).options.map { it.text })
        assertEquals(listOf(0, 1, 2), (loaded.first() as MultipleChoiceTask).options.map { it.position })
        assertEquals(1, (loaded.first() as MultipleChoiceTask).options.count { it.isCorrect })
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
        assertEquals(listOf(original.options[2].id, original.options[0].id), (loadedTask as MultipleChoiceTask).options.map { it.id })
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
        val options = (game.stations.first().tasks.single() as MultipleChoiceTask).options
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
        val task = game.stations.first().tasks.single() as MultipleChoiceTask
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
    fun firstTryOnlyAwardsPointsOnlyForCorrectFirstSubmission() {
        val task = task("Question").copy(points = 280)
        val game = createGame(listOf(task), TaskScoringMode.FIRST_TRY_ONLY)
        val session = repository.startOrResumeSession(game.id)
        assertEquals(TaskScoringMode.FIRST_TRY_ONLY, session.session.scoringMode)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { !it.isCorrect }.id)
        val incorrect = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
        assertFalse(incorrect.isCorrect)
        assertEquals(0, incorrect.pointsAwarded)

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { it.isCorrect }.id)
        val retried = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted

        assertTrue(retried.isCorrect)
        assertEquals(0, retried.pointsAwarded)
        assertEquals(0L, retried.progress.score)
        assertEquals(1, retried.progress.completedTaskCount)
        assertTrue(game.stations.first().id in retried.progress.completedStationIds)

        val firstCorrectTask = task("First answer is correct").copy(points = 280)
        val firstCorrectGame = createGame(listOf(firstCorrectTask), TaskScoringMode.FIRST_TRY_ONLY)
        val firstCorrectSession = repository.startOrResumeSession(firstCorrectGame.id)
        repository.recordStationScan(firstCorrectSession.session.id, firstCorrectGame.stations.first().qrToken)
        repository.savePendingTaskSelection(
            firstCorrectSession.session.id,
            firstCorrectTask.id,
            firstCorrectTask.options.first { it.isCorrect }.id
        )
        val firstCorrect = repository.submitTaskAttempt(firstCorrectSession.session.id, firstCorrectTask.id)
            as TaskSubmissionResult.Submitted
        assertTrue(firstCorrect.isCorrect)
        assertEquals(280, firstCorrect.pointsAwarded)
        assertEquals(280L, firstCorrect.progress.score)
    }

    @Test
    fun continueAfterWrongAnswerFinalizesTaskWithoutPointsAndDiscardsPendingRetry() {
        val task = task("Question").copy(points = 180)
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { !it.isCorrect }.id)
        repository.submitTaskAttempt(session.session.id, task.id)
        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { it.isCorrect }.id)

        val continued = repository.continueTaskAfterWrongAnswer(session.session.id, task.id)

        assertEquals(0L, continued.score)
        assertEquals(1, continued.completedTaskCount)
        assertNull(continued.currentStation)
        assertTrue(game.stations.first().id in continued.completedStationIds)
        assertEquals("Second", continued.nextStation?.title)
        val finalizedAttempt = databaseHelper.readableDatabase.rawQuery(
            "SELECT correctness, awarded_points, is_finalized FROM task_attempts WHERE session_id = ? AND task_id = ? AND submitted_at IS NOT NULL",
            arrayOf(session.session.id.toString(), task.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
        }
        assertEquals(listOf(0, 0, 1), finalizedAttempt)
        assertEquals(0, databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM task_attempts WHERE session_id = ? AND task_id = ? AND submitted_at IS NULL",
            arrayOf(session.session.id.toString(), task.id)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertTrue(runCatching {
            repository.continueTaskAfterWrongAnswer(session.session.id, task.id)
        }.isFailure)
    }

    @Test
    fun scoringPolicyEditsApplyToFutureRunsButNotAnActiveRun() {
        val game = createGame()
        val firstRun = repository.startOrResumeSession(game.id)

        assertTrue(repository.updateGameScoringMode(game.id, TaskScoringMode.FIRST_TRY_ONLY))
        assertEquals(TaskScoringMode.FIRST_TRY_ONLY, repository.getGame(game.id)?.scoringMode)
        assertEquals(TaskScoringMode.EVENTUAL_CORRECT, repository.getSessionProgress(firstRun.session.id)?.session?.scoringMode)

        game.stations.forEach { repository.recordStationScan(firstRun.session.id, it.qrToken) }
        val nextRun = repository.startOrResumeSession(game.id)
        assertEquals(TaskScoringMode.FIRST_TRY_ONLY, nextRun.session.scoringMode)
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
        assertEquals(listOf("Wrong", "Correct"), (game.stations.first().tasks.single() as MultipleChoiceTask).options.map { it.text })
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
    fun v6MigrationPreservesAwardsAndDefaultsExistingScoringPolicy() {
        val oldDatabase = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        createV5Schema(oldDatabase)
        oldDatabase.execSQL("ALTER TABLE game_sessions ADD COLUMN total_task_count INTEGER")
        oldDatabase.execSQL("ALTER TABLE game_sessions ADD COLUMN total_station_count INTEGER")
        oldDatabase.execSQL("ALTER TABLE tasks ADD COLUMN points INTEGER NOT NULL DEFAULT 100")
        oldDatabase.execSQL("ALTER TABLE task_attempts ADD COLUMN awarded_points INTEGER NOT NULL DEFAULT 0")
        oldDatabase.execSQL(
            "INSERT INTO games(id, title, game_uuid, content_version, updated_at) VALUES (1, 'Policy game', 'game-uuid', 2, 10)"
        )
        oldDatabase.execSQL(
            """INSERT INTO stations(id, game_id, title, body_text, position, qr_token)
                VALUES (11, 1, 'First', 'Text', 0, 'first-qr'), (12, 1, 'Second', 'Text', 1, 'second-qr')"""
        )
        oldDatabase.execSQL(
            """INSERT INTO game_sessions(
                id, game_id, status, created_at, updated_at, completed_at, total_task_count, total_station_count
            ) VALUES (21, 1, 'COMPLETED', 100, 200, 200, 1, 2)"""
        )
        oldDatabase.execSQL(
            """INSERT INTO station_visits(session_id, station_id, visited_at, completed_at)
                VALUES (21, 11, 110, 120), (21, 12, 130, 140)"""
        )
        oldDatabase.execSQL("INSERT INTO tasks(id, station_id, type, prompt, position, points) VALUES ('policy-task', 11, 'MULTIPLE_CHOICE', 'Policy question', 0, 235)")
        oldDatabase.execSQL(
            """INSERT INTO task_options(id, task_id, text, position, is_correct)
                VALUES ('wrong-option', 'policy-task', 'Wrong', 0, 0), ('correct-option', 'policy-task', 'Correct', 1, 1)"""
        )
        oldDatabase.execSQL(
            """INSERT INTO task_attempts(
                id, session_id, task_id, selected_option_id, selected_option_text_snapshot,
                prompt_text_snapshot, correctness, selected_at, submitted_at, awarded_points
            ) VALUES ('earned-attempt', 21, 'policy-task', 'correct-option', 'Correct',
                'Policy question', 1, 115, 116, 235)"""
        )
        oldDatabase.execSQL("PRAGMA user_version = 6")
        oldDatabase.close()
        databaseHelper.close()

        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val migratedGame = checkNotNull(repository.getGame(1))
        val migratedSession = checkNotNull(repository.getSessionProgress(21))
        val migratedHistory = repository.getCompletedSessionResults().single()

        assertEquals(TaskScoringMode.EVENTUAL_CORRECT, migratedGame.scoringMode)
        assertEquals(TaskScoringMode.EVENTUAL_CORRECT, migratedSession.session.scoringMode)
        assertEquals(235, migratedGame.stations.first().tasks.single().points)
        assertEquals(235L, migratedSession.score)
        assertEquals(1, migratedSession.completedTaskCount)
        assertEquals(235L, migratedHistory.score)
        assertEquals(1, migratedHistory.completedTaskCount)
        assertEquals(0, databaseHelper.readableDatabase.rawQuery(
            "SELECT is_finalized FROM task_attempts WHERE id = 'earned-attempt'",
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

    @Test
    fun trueFalseAndShortTextTasksPersistAndAreScoredAfterEvaluation() {
        val truth = TrueFalseTaskDraft(UUID.randomUUID().toString(), "Is the sky blue?", 0, true, 60)
        val text = ShortTextTaskDraft(UUID.randomUUID().toString(), "Name the city", 1, "  Žilina  ", 90)
        val game = createGame(listOf(truth, text))
        val loaded = checkNotNull(repository.getGame(game.id)).stations.first().tasks
        assertTrue(loaded[0] is TrueFalseTask)
        assertTrue(loaded[1] is ShortTextTask)
        assertEquals("Žilina", (loaded[1] as ShortTextTask).correctAnswer)

        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskAnswer(session.session.id, truth.id, TaskAnswer.BooleanValue(false))
        val wrong = repository.submitTaskAttempt(session.session.id, truth.id) as TaskSubmissionResult.Submitted
        assertFalse(wrong.isCorrect)
        assertEquals(0L, wrong.progress.score)
        repository.savePendingTaskAnswer(session.session.id, truth.id, TaskAnswer.BooleanValue(true))
        val right = repository.submitTaskAttempt(session.session.id, truth.id) as TaskSubmissionResult.Submitted
        assertTrue(right.isCorrect)
        assertEquals(60L, right.progress.score)

        repository.savePendingTaskAnswer(session.session.id, text.id, TaskAnswer.TextValue("  ŽILINA  "))
        val textRight = repository.submitTaskAttempt(session.session.id, text.id) as TaskSubmissionResult.Submitted
        assertTrue(textRight.isCorrect)
        assertEquals(150L, textRight.progress.score)
        assertEquals(2, textRight.progress.completedTaskCount)
    }

    @Test
    fun editingTrueFalseAndShortTextDefinitionsPersistsNewCorrectAnswers() {
        val truth = TrueFalseTaskDraft(UUID.randomUUID().toString(), "Original", 0, true, 5)
        val text = ShortTextTaskDraft(UUID.randomUUID().toString(), "Original text", 1, "Old", 8)
        val game = createGame(listOf(truth, text))
        val station = game.stations.first()
        repository.updateStationContent(
            stationId = station.id,
            title = station.title,
            bodyText = station.bodyText,
            tasks = listOf(truth.copy(prompt = "Updated", correctAnswer = false), text.copy(correctAnswer = " New "))
        )
        val tasks = checkNotNull(repository.getGame(game.id)).stations.first().tasks
        assertEquals("Updated", tasks[0].prompt)
        assertFalse((tasks[0] as TrueFalseTask).correctAnswer)
        assertEquals("New", (tasks[1] as ShortTextTask).correctAnswer)
        assertEquals(listOf(5, 8), tasks.map { it.points })
    }

    @Test
    fun falseCorrectAnswerHonorsFirstTryOnlyScoring() {
        val truth = TrueFalseTaskDraft(UUID.randomUUID().toString(), "Statement", 0, false, 40)
        val game = createGame(listOf(truth), TaskScoringMode.FIRST_TRY_ONLY)
        val session = repository.startOrResumeSession(game.id).session
        repository.recordStationScan(session.id, game.stations.first().qrToken)
        repository.savePendingTaskAnswer(session.id, truth.id, TaskAnswer.BooleanValue(true))
        assertFalse((repository.submitTaskAttempt(session.id, truth.id) as TaskSubmissionResult.Submitted).isCorrect)
        repository.savePendingTaskAnswer(session.id, truth.id, TaskAnswer.BooleanValue(false))
        val correct = repository.submitTaskAttempt(session.id, truth.id) as TaskSubmissionResult.Submitted
        assertTrue(correct.isCorrect)
        assertEquals(0, correct.pointsAwarded)
        assertEquals(0L, correct.progress.score)
    }

    @Test
    fun shortTextAnswerPreservesDiacriticsAndPendingValueRestores() {
        val text = ShortTextTaskDraft(UUID.randomUUID().toString(), "Say café", 0, "café", 15)
        val game = createGame(listOf(text))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)
        repository.savePendingTaskAnswer(session.session.id, text.id, TaskAnswer.TextValue("cafe"))
        val pending = checkNotNull(repository.getSessionProgress(session.session.id)).taskProgress.single()
        assertEquals("cafe", pending.pendingAttempt?.selectedOptionTextSnapshot)
        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val restored = checkNotNull(repository.getSessionProgress(session.session.id))
        assertEquals("cafe", restored.taskProgress.single().pendingAttempt?.selectedOptionTextSnapshot)
        val result = repository.submitTaskAttempt(session.session.id, text.id) as TaskSubmissionResult.Submitted
        assertFalse(result.isCorrect)
        assertEquals(0L, result.progress.score)
    }

    @Test
    fun timerStartsOnceAndTimeoutCompletesTaskWithoutPoints() {
        var now = 1_000_000L
        repository = GameRepository(databaseHelper) { now }
        val timed = task("Timed", timeLimitSeconds = 10)
        val game = createGame(listOf(timed))
        repository.updateGameRoute(game.id, GameRouteDraft(
            GameProgressionMode.BRANCHED, 0, listOf(
                RouteTransitionDraft(0, RouteCondition.CORRECT, null),
                RouteTransitionDraft(0, RouteCondition.INCORRECT, 1),
                RouteTransitionDraft(1, RouteCondition.INFO, null)
            )
        ))
        val session = repository.startOrResumeSession(game.id).session
        repository.recordStationScan(session.id, game.stations.first().qrToken)

        val deadline = checkNotNull(repository.activateTaskTimer(session.id, timed.id))
        assertEquals(deadline, repository.activateTaskTimer(session.id, timed.id))
        assertEquals(deadline, now + 10_000)
        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper) { now }
        assertEquals(deadline, repository.getSessionProgress(session.id)?.taskProgress?.single()?.deadlineAt)
        now = deadline
        val expired = checkNotNull(repository.timeoutTask(session.id, timed.id))
        val timeoutAttempt = databaseHelper.readableDatabase.rawQuery(
            "SELECT is_timed_out, awarded_points FROM task_attempts WHERE session_id = ? AND task_id = ?",
            arrayOf(session.id.toString(), timed.id)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) to cursor.getInt(1) }
        assertEquals(1, timeoutAttempt.first)
        assertEquals(0, timeoutAttempt.second)
        assertEquals(0L, expired.score)
        assertNull(expired.currentStation)
        assertEquals(game.stations[1].id, expired.nextStation?.id)
        assertNull(repository.timeoutTask(session.id, timed.id))
        assertTrue(repository.submitTaskAttempt(session.id, timed.id) is TaskSubmissionResult.TaskAlreadyCompleted)
    }

    @Test
    fun deadlineWinsAgainstLateAnswerAndAnswerBeforeDeadlineIsAccepted() {
        var now = 5_000L
        repository = GameRepository(databaseHelper) { now }
        val timed = task("Timed", timeLimitSeconds = 10)
        val game = createGame(listOf(timed))
        val session = repository.startOrResumeSession(game.id).session
        repository.recordStationScan(session.id, game.stations.first().qrToken)
        val deadline = checkNotNull(repository.activateTaskTimer(session.id, timed.id))
        repository.savePendingTaskSelection(session.id, timed.id, timed.options.first { it.isCorrect }.id)
        now = deadline - 1
        val onTime = repository.submitTaskAttempt(session.id, timed.id) as TaskSubmissionResult.Submitted
        assertTrue(onTime.isCorrect)
        assertEquals(0, databaseHelper.readableDatabase.rawQuery(
            "SELECT is_timed_out FROM task_attempts WHERE session_id = ? AND task_id = ?",
            arrayOf(session.id.toString(), timed.id)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })

        val laterTask = task("Late", timeLimitSeconds = 10)
        val laterGame = createGame(listOf(laterTask))
        val lateSession = repository.startOrResumeSession(laterGame.id).session
        repository.recordStationScan(lateSession.id, laterGame.stations.first().qrToken)
        val lateDeadline = checkNotNull(repository.activateTaskTimer(lateSession.id, laterTask.id))
        repository.savePendingTaskSelection(lateSession.id, laterTask.id, laterTask.options.first { it.isCorrect }.id)
        now = lateDeadline
        val expired = repository.submitTaskAttempt(lateSession.id, laterTask.id) as TaskSubmissionResult.TimedOut
        assertEquals(1, databaseHelper.readableDatabase.rawQuery(
            "SELECT is_timed_out FROM task_attempts WHERE session_id = ? AND task_id = ?",
            arrayOf(lateSession.id.toString(), laterTask.id)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertEquals(0L, expired.progress.score)
    }

    @Test
    fun trueFalseAndShortTextTasksCanTimeout() {
        var now = 10_000L
        repository = GameRepository(databaseHelper) { now }
        val timedTasks = listOf<StationTaskDraft>(
            TrueFalseTaskDraft(UUID.randomUUID().toString(), "True?", 0, true, 25, 10),
            ShortTextTaskDraft(UUID.randomUUID().toString(), "Name it", 0, "Bratislava", 25, 10)
        )
        timedTasks.forEach { task ->
            val game = createGame(listOf(task))
            val session = repository.startOrResumeSession(game.id).session
            repository.recordStationScan(session.id, game.stations.first().qrToken)
            now = checkNotNull(repository.activateTaskTimer(session.id, task.id))
            assertNotNull(repository.timeoutTask(session.id, task.id))
            assertEquals(1, databaseHelper.readableDatabase.rawQuery(
                "SELECT is_timed_out FROM task_attempts WHERE session_id = ? AND task_id = ?",
                arrayOf(session.id.toString(), task.id)
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        }
    }

    @Test
    fun v7MigrationPreservesTasksOptionsAttemptsAndMakesOptionNullable() {
        databaseHelper.close()
        val old = context.openOrCreateDatabase(databaseName, 0, null)
        createV7Schema(old)
        old.execSQL("INSERT INTO games VALUES(1, 'Legacy', 'game-uuid', 1, 10, 'EVENTUAL_CORRECT')")
        old.execSQL("INSERT INTO stations VALUES(1, 1, 'First', '', 0, 'qr-one')")
        old.execSQL("INSERT INTO game_sessions VALUES(1, 1, 'IN_PROGRESS', 10, 20, NULL, 'FIRST_TRY_ONLY', 1, 2)")
        old.execSQL("INSERT INTO tasks VALUES('task-v7', 1, 'MULTIPLE_CHOICE', 'Prompt', 0, 12)")
        old.execSQL("INSERT INTO task_options VALUES('option-v7', 'task-v7', 'Answer', 0, 1)")
        old.execSQL("INSERT INTO task_options VALUES('option-wrong', 'task-v7', 'Wrong', 1, 0)")
        old.execSQL("INSERT INTO task_attempts VALUES('pending-v7', 1, 'task-v7', 'option-v7', 'Answer', 'Prompt', NULL, 30, NULL, 0, 0)")
        old.execSQL("INSERT INTO task_attempts VALUES('submitted-v7', 1, 'task-v7', 'option-wrong', 'Wrong', 'Prompt', 0, 31, 32, 0, 0)")
        old.execSQL("PRAGMA user_version = 7")
        old.close()

        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val db = databaseHelper.readableDatabase
        assertEquals(10, db.version)
        assertNull(db.rawQuery("SELECT time_limit_seconds FROM tasks WHERE id = 'task-v7'", null).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getInt(0)
        })
        assertEquals(0, db.rawQuery("SELECT is_timed_out FROM task_attempts WHERE id = 'submitted-v7'", null).use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals("MULTIPLE_CHOICE", db.rawQuery("SELECT type FROM tasks WHERE id = 'task-v7'", null).use {
            it.moveToFirst(); it.getString(0)
        })
        assertEquals(2, db.rawQuery("SELECT COUNT(*) FROM task_options WHERE task_id = 'task-v7'", null).use {
            it.moveToFirst(); it.getInt(0)
        })
        assertEquals(listOf("pending-v7", "submitted-v7"), db.rawQuery(
            "SELECT id FROM task_attempts WHERE task_id = 'task-v7' ORDER BY selected_at", null
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } })
        assertEquals(0, db.rawQuery("SELECT awarded_points FROM task_attempts WHERE id = 'pending-v7'", null).use {
            it.moveToFirst(); it.getInt(0)
        })
        val optionIsNullable = db.rawQuery("PRAGMA table_info(task_attempts)", null).use { cursor ->
            var nullable = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "selected_option_id") nullable = cursor.getInt(3) == 0
            nullable
        }
        assertTrue(optionIsNullable)
    }

    private fun createGame(
        tasks: List<StationTaskDraft> = emptyList(),
        scoringMode: TaskScoringMode = TaskScoringMode.EVENTUAL_CORRECT
    ) = repository.createGame(
        GameDraft(
            title = "Task game",
            stations = listOf(
                StationDraft("First", "First text", tasks),
                StationDraft("Second", "Second text")
            ),
            scoringMode = scoringMode
        )
    )

    private fun task(
        prompt: String,
        position: Int = 0,
        optionTexts: List<String> = listOf("Wrong", "Correct"),
        timeLimitSeconds: Int? = null
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
        },
        timeLimitSeconds = timeLimitSeconds
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

    private fun createV7Schema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE games (id INTEGER PRIMARY KEY, title TEXT NOT NULL, game_uuid TEXT NOT NULL, content_version INTEGER NOT NULL, updated_at INTEGER NOT NULL, scoring_mode TEXT NOT NULL)")
        db.execSQL("CREATE TABLE stations (id INTEGER PRIMARY KEY, game_id INTEGER NOT NULL, title TEXT NOT NULL, body_text TEXT NOT NULL, position INTEGER NOT NULL, qr_token TEXT NOT NULL)")
        db.execSQL("CREATE TABLE game_sessions (id INTEGER PRIMARY KEY, game_id INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER, scoring_mode TEXT NOT NULL, total_task_count INTEGER, total_station_count INTEGER)")
        db.execSQL("""CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, station_id INTEGER NOT NULL, type TEXT NOT NULL CHECK(type = 'MULTIPLE_CHOICE'), prompt TEXT NOT NULL, position INTEGER NOT NULL, points INTEGER NOT NULL DEFAULT 100)""")
        db.execSQL("CREATE TABLE task_options (id TEXT PRIMARY KEY NOT NULL, task_id TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, is_correct INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE task_attempts (id TEXT PRIMARY KEY NOT NULL, session_id INTEGER NOT NULL, task_id TEXT NOT NULL, selected_option_id TEXT NOT NULL, selected_option_text_snapshot TEXT NOT NULL, prompt_text_snapshot TEXT NOT NULL, correctness INTEGER, selected_at INTEGER NOT NULL, submitted_at INTEGER, awarded_points INTEGER NOT NULL DEFAULT 0, is_finalized INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX index_tasks_station_id ON tasks(station_id)")
        db.execSQL("CREATE INDEX index_task_options_task_id ON task_options(task_id)")
        db.execSQL("CREATE UNIQUE INDEX index_task_options_one_correct ON task_options(task_id) WHERE is_correct = 1")
        db.execSQL("CREATE INDEX index_task_attempts_session_task ON task_attempts(session_id, task_id, selected_at)")
        db.execSQL("CREATE UNIQUE INDEX index_task_attempts_one_pending ON task_attempts(session_id, task_id) WHERE submitted_at IS NULL")
    }
}
