package com.qrhry.app.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationScanResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameSessionRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var databaseHelper: GameDatabaseHelper
    private lateinit var repository: GameRepository

    @Before
    fun setUp() {
        databaseName = "qrhry-session-test-${UUID.randomUUID()}.db"
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
    }

    @After
    fun tearDown() {
        databaseHelper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun startCreatesSessionAndSecondStartResumesIt() {
        val game = createGame("Trail")

        val first = repository.startOrResumeSession(game.id)
        val resumed = repository.startOrResumeSession(game.id)

        assertEquals(first.session.id, resumed.session.id)
        assertEquals(GameSessionStatus.IN_PROGRESS, first.session.status)
        assertEquals(listOf(first.session.id), repository.getActiveSessions().map { it.id })
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseAllowsOnlyOneInProgressSessionPerGame() {
        val game = createGame("Trail")
        repository.startOrResumeSession(game.id)

        databaseHelper.writableDatabase.execSQL(
            """INSERT INTO game_sessions(game_id, status, created_at, updated_at)
                VALUES (?, 'IN_PROGRESS', ?, ?)""",
            arrayOf(game.id, System.currentTimeMillis(), System.currentTimeMillis())
        )
    }

    @Test
    fun visitsDetermineNextStationByPosition() {
        val game = createGame("Trail", "First", "Second", "Third")
        val progress = repository.startOrResumeSession(game.id)

        assertEquals("First", progress.nextStation?.title)
        val result = repository.recordStationScan(progress.session.id, game.stations[0].qrToken)
        assertEquals("Second", (result as StationScanResult.Accepted).progress.nextStation?.title)
    }

    @Test
    fun rejectsStationFromAnotherGame() {
        val firstGame = createGame("First game")
        val otherGame = createGame("Other game")
        val session = repository.startOrResumeSession(firstGame.id)

        val result = repository.recordStationScan(session.session.id, otherGame.stations[0].qrToken)

        assertTrue(result is StationScanResult.WrongGame)
        assertEquals(0, repository.getSessionProgress(session.session.id)?.completedStationCount)
    }

    @Test
    fun rejectsLaterStationOutOfOrder() {
        val game = createGame("Trail")
        val session = repository.startOrResumeSession(game.id)

        val result = repository.recordStationScan(session.session.id, game.stations[1].qrToken)

        assertTrue(result is StationScanResult.OutOfOrder)
        assertEquals("First", (result as StationScanResult.OutOfOrder).expectedStation.title)
        assertEquals(0, repository.getSessionProgress(session.session.id)?.completedStationCount)
    }

    @Test
    fun successfulVisitIsRecordedAndRescanDoesNotDuplicateIt() {
        val game = createGame("Trail")
        val session = repository.startOrResumeSession(game.id)
        val firstVisit = repository.recordStationScan(session.session.id, game.stations[0].qrToken)
        val rescan = repository.recordStationScan(session.session.id, game.stations[0].qrToken)

        assertTrue(firstVisit is StationScanResult.Accepted)
        assertTrue(rescan is StationScanResult.AlreadyVisited)
        assertEquals(1, (rescan as StationScanResult.AlreadyVisited).progress.completedStationCount)
        assertEquals("Second", rescan.progress.nextStation?.title)
    }

    @Test
    fun finalVisitCompletesSessionAndNewRunCanBeStarted() {
        val game = createGame("Trail")
        val firstRun = repository.startOrResumeSession(game.id)
        repository.recordStationScan(firstRun.session.id, game.stations[0].qrToken)

        val finalVisit = repository.recordStationScan(firstRun.session.id, game.stations[1].qrToken)
        val completedProgress = (finalVisit as StationScanResult.Accepted).progress

        assertEquals(GameSessionStatus.COMPLETED, completedProgress.session.status)
        assertNotNull(completedProgress.session.completedAt)
        assertNull(completedProgress.nextStation)
        val secondRun = repository.startOrResumeSession(game.id)
        assertNotEquals(firstRun.session.id, secondRun.session.id)
        assertEquals(GameSessionStatus.COMPLETED, repository.getSessionProgress(firstRun.session.id)?.session?.status)
    }

    @Test
    fun reopeningDatabaseRestoresActiveSessionProgress() {
        val game = createGame("Trail")
        val session = repository.startOrResumeSession(game.id)
        repository.recordStationScan(session.session.id, game.stations[0].qrToken)
        databaseHelper.close()

        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val restored = repository.getSessionProgress(session.session.id)

        assertEquals(listOf(session.session.id), repository.getActiveSessions().map { it.id })
        assertEquals(1, restored?.completedStationCount)
        assertEquals("Second", restored?.nextStation?.title)
    }

    @Test
    fun v2ToV3MigrationPreservesGamesStationsAndQrTokens() {
        val oldDatabase = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(databaseName),
            null
        )
        createV2Schema(oldDatabase)
        oldDatabase.execSQL("INSERT INTO games(id, title) VALUES (11, 'Preserved game')")
        oldDatabase.execSQL(
            """INSERT INTO stations(id, game_id, title, body_text, position, qr_token)
                VALUES (21, 11, 'Preserved station', 'Preserved text', 0, ? )""",
            arrayOf("12345678-1234-1234-1234-123456789abc")
        )
        oldDatabase.execSQL("PRAGMA user_version = 2")
        oldDatabase.close()
        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)

        val migratedGame = repository.getGame(11)
        val station = migratedGame!!.stations.single()

        assertEquals("Preserved game", migratedGame.title)
        assertEquals(21L, station.id)
        assertEquals("Preserved station", station.title)
        assertEquals("Preserved text", station.bodyText)
        assertEquals("12345678-1234-1234-1234-123456789abc", station.qrToken)
        assertNotNull(repository.startOrResumeSession(11))
    }

    @Test
    fun completedSessionsRemainAsHistory() {
        val game = createGame("Trail")
        val firstRun = repository.startOrResumeSession(game.id)
        game.stations.forEach { repository.recordStationScan(firstRun.session.id, it.qrToken) }
        val secondRun = repository.startOrResumeSession(game.id)
        game.stations.forEach { repository.recordStationScan(secondRun.session.id, it.qrToken) }

        assertEquals(GameSessionStatus.COMPLETED, repository.getSessionProgress(firstRun.session.id)?.session?.status)
        assertEquals(GameSessionStatus.COMPLETED, repository.getSessionProgress(secondRun.session.id)?.session?.status)
        assertTrue(repository.getActiveSessions().isEmpty())
    }

    private fun createGame(title: String, vararg stationTitles: String) = repository.createGame(
        GameDraft(
            title,
            (stationTitles.toList().ifEmpty { listOf("First", "Second") })
                .map { StationDraft(it, "Text for $it") }
        )
    )

    private fun createV2Schema(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE games (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL CHECK (length(trim(title)) > 0))"
        )
        database.execSQL(
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
        database.execSQL("CREATE INDEX index_stations_game_id ON stations(game_id)")
    }
}