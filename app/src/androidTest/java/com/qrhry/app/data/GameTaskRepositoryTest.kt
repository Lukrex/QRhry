package com.qrhry.app.data

import android.database.sqlite.SQLiteConstraintException
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
        val updatedTask = original.copy(options = updatedOptions)

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
        listOf(noCorrectAnswer, multipleCorrectAnswers, oneOption, blankOption, invalidOptionOrder, blankPrompt).forEach { invalid ->
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

        assertEquals(game.stations.first().id, restored.currentStation?.id)
        assertEquals(task.options.first().id, taskProgress.pendingAttempt?.selectedOptionId)
        assertEquals(task.options.first().text, taskProgress.pendingAttempt?.selectedOptionTextSnapshot)
        assertEquals(task.prompt, taskProgress.pendingAttempt?.promptTextSnapshot)
        assertNull(taskProgress.pendingAttempt?.submittedAt)
        assertEquals("First", restored.nextStation?.title)
    }

    @Test
    fun incorrectAttemptCanBeRetriedAndCorrectAnswerAdvances() {
        val task = task("Question")
        val game = createGame(listOf(task))
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations.first().qrToken)

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { !it.isCorrect }.id)
        val incorrect = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
        assertFalse(incorrect.isCorrect)
        assertTrue(game.stations.first().id !in incorrect.progress.completedStationIds)
        assertEquals("First", incorrect.progress.nextStation?.title)
        assertEquals(listOf(false), incorrect.progress.taskProgress.single().attempts.map { it.correctness })

        repository.savePendingTaskSelection(session.session.id, task.id, task.options.first { it.isCorrect }.id)
        val correct = repository.submitTaskAttempt(session.session.id, task.id) as TaskSubmissionResult.Submitted
        assertTrue(correct.isCorrect)
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
}