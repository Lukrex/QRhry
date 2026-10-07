package com.qrhry.app.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.qr.StationQrCodeGenerator
import com.qrhry.app.qr.StationQrPayload
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var databaseHelper: GameDatabaseHelper
    private lateinit var repository: GameRepository

    @Before
    fun setUp() {
        databaseName = "qrhry-test-${UUID.randomUUID()}.db"
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
    }

    @After
    fun tearDown() {
        databaseHelper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun savesAndRetrievesGameWithOrderedStations() {
        val created = repository.createGame(
            GameDraft(
                title = "Campus trail",
                stations = listOf(
                    StationDraft("Start", "Meet at the gate"),
                    StationDraft("Library", "Find the entrance")
                )
            )
        )

        val loaded = repository.getGame(created.id)

        assertEquals("Campus trail", loaded?.title)
        assertEquals(listOf("Start", "Library"), loaded?.stations?.map { it.title })
        assertEquals(listOf(0, 1), loaded?.stations?.map { it.position })
        assertEquals("Meet at the gate", loaded?.stations?.first()?.bodyText)
        assertEquals(created, loaded)
        assertEquals(listOf(created), repository.getAllGames())
        val lookup = repository.findStationByQrToken(created.stations.first().qrToken)
        assertEquals(created.title, lookup?.gameTitle)
        assertEquals(created.stations.first(), lookup?.station)
        assertEquals(2, created.stations.map { it.qrToken }.toSet().size)
    }

    @Test
    fun migrationAddsTokensAndPreservesExistingStations() {
        val oldDatabase = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(databaseName),
            null
        )
        oldDatabase.execSQL(
            "CREATE TABLE games (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL CHECK (length(trim(title)) > 0))"
        )
        oldDatabase.execSQL(
            """CREATE TABLE stations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_id INTEGER NOT NULL,
                title TEXT NOT NULL CHECK (length(trim(title)) > 0),
                body_text TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL CHECK (position >= 0),
                FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
                UNIQUE (game_id, position)
            )"""
        )
        oldDatabase.execSQL("CREATE INDEX index_stations_game_id ON stations(game_id)")
        oldDatabase.execSQL("INSERT INTO games(id, title) VALUES (7, 'Legacy game')")
        oldDatabase.execSQL(
            "INSERT INTO stations(id, game_id, title, body_text, position) VALUES (9, 7, 'Legacy station', 'Kept text', 0)"
        )
        oldDatabase.execSQL("PRAGMA user_version = 1")
        oldDatabase.close()
        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)

        val migrated = repository.getGame(7)
        val station = migrated!!.stations.single()

        assertEquals("Legacy station", station.title)
        assertEquals("Kept text", station.bodyText)
        assertEquals(9L, station.id)
        assertEquals(36, station.qrToken.length)
        assertEquals(station, repository.findStationByQrToken(station.qrToken)?.station)
    }

    @Test
    fun generatedQrBitmapDecodesToStationPayload() {
        val token = "9d05c4bd-82ad-4515-b5c2-435a92100001"
        val bitmap = StationQrCodeGenerator.createBitmap(token, size = 320)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text

        assertEquals(StationQrPayload.create(token), decoded)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun stationCannotReferenceMissingGame() {
        databaseHelper.writableDatabase.execSQL(
            """INSERT INTO stations(game_id, title, body_text, position)
                VALUES (?, ?, ?, ?)""",
            arrayOf(999L, "Orphan", "", 0)
        )
    }
}