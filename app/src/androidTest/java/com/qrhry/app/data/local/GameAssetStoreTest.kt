package com.qrhry.app.data.local

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qrhry.app.data.GameRepository
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationMedia
import com.qrhry.app.domain.StationMediaType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameAssetStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var databaseHelper: GameDatabaseHelper
    private lateinit var repository: GameRepository
    private lateinit var assetStore: GameAssetStore
    private val importedAssets = mutableListOf<ImportedAsset>()
    private var importedGameUuid: String? = null
    private var importedRelativePath: String? = null

    @Before
    fun setUp() {
        databaseName = "qrhry-assets-${UUID.randomUUID()}.db"
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        assetStore = GameAssetStore(context.filesDir)
    }

    @After
    fun tearDown() {
        databaseHelper.close()
        if (importedGameUuid != null && importedRelativePath != null) {
            assetStore.delete(importedGameUuid!!, importedRelativePath!!)
        }
        importedAssets.forEach(assetStore::discard)
        context.deleteDatabase(databaseName)
    }

    @Test
    fun importedMediaResolvesFromPersistedGameRootAfterDatabaseReopen() {
        val imageBytes = createPngBytes()
        val audioBytes = createWaveBytes()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val imageUri = Uri.parse("content://com.qrhry.app.asset-test/image")
        val audioUri = Uri.parse("content://com.qrhry.app.asset-test/audio")
        val resolver = testContext.contentResolver
        val game = repository.createGame(
            GameDraft(
                "Asset test",
                listOf(StationDraft("Station", "Text"), StationDraft("Second station", "Text"))
            )
        )
        importedGameUuid = game.gameUuid
        val stationId = game.stations.first().id

        val imageAsset = assetStore.stageImport(
            resolver = resolver,
            source = imageUri,
            gameUuid = game.gameUuid,
            mediaType = StationMediaType.IMAGE,
            originalFilename = "fixture-image.png"
        )
        val audioAsset = assetStore.stageImport(
            resolver = resolver,
            source = audioUri,
            gameUuid = game.gameUuid,
            mediaType = StationMediaType.AUDIO,
            originalFilename = "fixture-audio.wav"
        )
        importedAssets += listOf(imageAsset, audioAsset)
        importedRelativePath = imageAsset.relativePath
        listOf(imageAsset, audioAsset).forEach { asset ->
            assertTrue(asset.temporaryFile.isFile)
            assertFalse(asset.finalFile.exists())
            assetStore.promote(asset)
            assertTrue("Promoted file missing: ${asset.finalFile}", asset.finalFile.isFile)
        }
        assertArrayEquals(imageBytes, imageAsset.finalFile.readBytes())
        assertArrayEquals(audioBytes, audioAsset.finalFile.readBytes())

        val imageMedia = imageAsset.toStationMedia(stationId, StationMediaType.IMAGE)
        val audioMedia = audioAsset.toStationMedia(stationId, StationMediaType.AUDIO)
        repository.updateStationContent(
            stationId = stationId,
            title = "Station",
            bodyText = "Text",
            replacements = listOf(imageMedia, audioMedia)
        )
        assertEquals(imageAsset.relativePath, readStoredRelativePath(databaseHelper.writableDatabase, imageMedia.id))
        assertEquals(audioAsset.relativePath, readStoredRelativePath(databaseHelper.writableDatabase, audioMedia.id))

        databaseHelper.close()
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        val reopenedGame = checkNotNull(repository.getGame(game.id))
        val reopenedMedia = reopenedGame.stations.first().media.associateBy { it.mediaType }
        val storedImage = checkNotNull(reopenedMedia[StationMediaType.IMAGE])
        val storedAudio = checkNotNull(reopenedMedia[StationMediaType.AUDIO])
        val resolvedImage = assetStore.resolve(reopenedGame.gameUuid, storedImage.relativePath)
        val resolvedAudio = assetStore.resolve(reopenedGame.gameUuid, storedAudio.relativePath)
        logImport(imageUri, imageAsset, storedImage.relativePath, resolvedImage, storedImage.mimeType)
        logImport(audioUri, audioAsset, storedAudio.relativePath, resolvedAudio, storedAudio.mimeType)

        assertEquals(game.gameUuid, reopenedGame.gameUuid)
        assertResolvedMedia(imageAsset, storedImage, resolvedImage, imageBytes, "image/png")
        assertResolvedMedia(audioAsset, storedAudio, resolvedAudio, audioBytes, "audio/wav")

        val decodedImage = BitmapFactory.decodeFile(resolvedImage.absolutePath)
        assertTrue("Imported PNG did not decode.", decodedImage != null)
        decodedImage?.recycle()
        val corruptImage = File(context.cacheDir, "corrupt-image-${UUID.randomUUID()}.png")
            .apply { writeBytes("not a PNG".toByteArray()) }
        assertEquals(null, BitmapFactory.decodeFile(corruptImage.absolutePath))
        corruptImage.delete()
        assertAudioPlaybackResult(resolvedAudio, expectSuccess = true)
        val corruptAudio = File(context.cacheDir, "corrupt-audio-${UUID.randomUUID()}.wav")
            .apply { writeBytes("not a WAV".toByteArray()) }
        assertAudioPlaybackResult(corruptAudio, expectSuccess = false)
        corruptAudio.delete()

        val reopenedSession = repository.startOrResumeSession(reopenedGame.id)
        assertEquals(
            setOf(imageAsset.relativePath, audioAsset.relativePath),
            reopenedSession.nextStation?.media?.map { it.relativePath }?.toSet()
        )
        assertTrue(assetStore.delete(reopenedGame.gameUuid, storedImage.relativePath))
        assertFalse(assetStore.fileExists(reopenedGame.gameUuid, storedImage.relativePath))
    }

    private fun ImportedAsset.toStationMedia(stationId: Long, mediaType: StationMediaType) = StationMedia(
        id = mediaId,
        stationId = stationId,
        mediaType = mediaType,
        relativePath = relativePath,
        mimeType = mimeType,
        originalFilename = originalFilename,
        checksum = checksum,
        byteSize = byteSize,
        displayOrder = if (mediaType == StationMediaType.IMAGE) 0 else 1
    )

    private fun assertResolvedMedia(
        asset: ImportedAsset,
        media: StationMedia,
        resolvedFile: File,
        sourceBytes: ByteArray,
        expectedMimeType: String
    ) {
        assertFalse(File(media.relativePath).isAbsolute)
        assertEquals(asset.relativePath, media.relativePath)
        assertEquals(asset.finalFile.canonicalPath, resolvedFile.canonicalPath)
        assertTrue("Resolved file missing: $resolvedFile", resolvedFile.isFile)
        assertEquals(sourceBytes.size.toLong(), resolvedFile.length())
        assertEquals(expectedMimeType, media.mimeType)
        assertEquals(sha256(sourceBytes), sha256(resolvedFile.readBytes()))
        assertArrayEquals(sourceBytes, resolvedFile.readBytes())
    }

    private fun assertAudioPlaybackResult(file: File, expectSuccess: Boolean) {
        val prepared = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>(null)
        var player: ExoPlayer? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build().also { activePlayer ->
                activePlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                            prepared.countDown()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playbackError.set(error)
                        prepared.countDown()
                    }
                })
                activePlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                activePlayer.prepare()
            }
        }
        try {
            assertTrue("Audio playback did not finish loading.", prepared.await(15, TimeUnit.SECONDS))
            if (expectSuccess) {
                assertEquals(null, playbackError.get())
            } else {
                assertTrue("Corrupt audio was accepted.", playbackError.get() != null)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player?.release()
            }
        }
    }

    private fun logImport(
        sourceUri: Uri,
        asset: ImportedAsset,
        relativePath: String,
        resolvedFile: File,
        mimeType: String
    ) {
        Log.i(
            "GameAssetStoreTest",
            "sourceUri=$sourceUri finalPath=${asset.finalFile.absolutePath} " +
                "relativePath=$relativePath resolvedPath=${resolvedFile.absolutePath} " +
                "exists=${resolvedFile.isFile} size=${resolvedFile.length()} mime=$mimeType"
        )
    }

    private fun createPngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val output = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun createWaveBytes(): ByteArray {
        val sampleRate = 8000
        val sampleBytes = sampleRate / 10 * 2
        return ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + sampleBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(sampleBytes)
        }.array()
    }

    private fun readStoredRelativePath(database: SQLiteDatabase, mediaId: String): String =
        database.rawQuery(
            "SELECT relative_path FROM station_media WHERE id = ?",
            arrayOf(mediaId)
        ).use { cursor ->
            assertTrue("No station_media row for $mediaId", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}