package com.qrhry.app.data.archive

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import com.qrhry.app.data.GameRepository
import com.qrhry.app.data.local.GameAssetStore
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.data.local.ImportedAsset
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameProgressionMode
import com.qrhry.app.domain.GameRouteDraft
import com.qrhry.app.domain.MultipleChoiceTask
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.TrueFalseTaskDraft
import com.qrhry.app.domain.ShortTextTaskDraft
import com.qrhry.app.domain.TaskAnswer
import com.qrhry.app.domain.TaskSubmissionResult
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.TaskScoringMode
import com.qrhry.app.domain.RouteCondition
import com.qrhry.app.domain.RouteTransitionDraft
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import com.qrhry.app.qr.QrPayloadParseResult
import com.qrhry.app.qr.StationQrPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertArrayEquals
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class GameArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var databaseHelper: GameDatabaseHelper
    private lateinit var repository: GameRepository
    private lateinit var assetStore: GameAssetStore
    private lateinit var archiveService: GameArchiveService
    private lateinit var archiveFile: File
    private val gameUuids = mutableSetOf<String>()

    @Before
    fun setUp() {
        databaseName = "qrhry-archive-${UUID.randomUUID()}.db"
        databaseHelper = GameDatabaseHelper(context, databaseName)
        repository = GameRepository(databaseHelper)
        assetStore = GameAssetStore(context.filesDir)
        archiveService = GameArchiveService(context.contentResolver, context.cacheDir, repository, assetStore)
        archiveFile = File(context.cacheDir, "qrhry-test-${UUID.randomUUID()}.qrhry")
    }

    @After
    fun tearDown() {
        databaseHelper.close()
        gameUuids.forEach { File(context.filesDir, "games/$it").deleteRecursively() }
        archiveFile.delete()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun exportImportRoundTripPreservesAuthoredContentAndCreatesFreshQrIdentities() {
        val task = taskDraft()
        val original = repository.createGame(
            GameDraft(
                title = "Portable trail",
                stations = listOf(
                    StationDraft("First", "Image and sound", listOf(task)),
                    StationDraft("Second", "Finish")
                ),
                scoringMode = TaskScoringMode.FIRST_TRY_ONLY
            )
        )
        gameUuids += original.gameUuid
        val testResolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val image = assetStore.stageImport(
            testResolver,
            Uri.parse("content://com.qrhry.app.asset-test/image"),
            original.gameUuid,
            StationMediaType.IMAGE,
            "fixture.png"
        )
        val audio = assetStore.stageImport(
            testResolver,
            Uri.parse("content://com.qrhry.app.asset-test/audio"),
            original.gameUuid,
            StationMediaType.AUDIO,
            "fixture.wav"
        )
        listOf(image, audio).forEach(assetStore::promote)
        repository.updateStationContent(
            stationId = original.stations.first().id,
            title = original.stations.first().title,
            bodyText = original.stations.first().bodyText,
            replacements = listOf(
                image.toStationMedia(original.stations.first().id, StationMediaType.IMAGE),
                audio.toStationMedia(original.stations.first().id, StationMediaType.AUDIO)
            )
        )
        val sourceSession = repository.startOrResumeSession(original.id).session

        archiveService.exportGameToFile(original.id, archiveFile)
        val preview = archiveService.inspectImport(Uri.fromFile(archiveFile))
        assertEquals(original.title, preview.title)
        assertEquals(2, preview.stationCount)
        assertEquals(1, preview.taskCount)
        assertEquals(2, preview.mediaCount)
        val imported = archiveService.confirmImport(preview.token)
        gameUuids += imported.gameUuid

        assertNotEquals(original.id, imported.id)
        assertNotEquals(original.gameUuid, imported.gameUuid)
        assertEquals(original.title, imported.title)
        assertEquals(TaskScoringMode.FIRST_TRY_ONLY, imported.scoringMode)
        assertEquals(original.stations.map { it.title }, imported.stations.map { it.title })
        assertNotEquals(original.stations.first().id, imported.stations.first().id)
        assertNotEquals(original.stations.first().qrToken, imported.stations.first().qrToken)
        assertTrue(StationQrPayload.parse(StationQrPayload.create(imported.stations.first().qrToken) ) is QrPayloadParseResult.Valid)
        assertNotEquals(original.stations.first().tasks.single().id, imported.stations.first().tasks.single().id)
        assertNotEquals(
            (original.stations.first().tasks.single() as MultipleChoiceTask).options.first().id,
            (imported.stations.first().tasks.single() as MultipleChoiceTask).options.first().id
        )
        assertEquals(task.points, imported.stations.first().tasks.single().points)
        assertEquals(task.prompt, imported.stations.first().tasks.single().prompt)
        assertEquals(task.options.map { it.text }, (imported.stations.first().tasks.single() as MultipleChoiceTask).options.map { it.text })
        assertEquals(task.options.map { it.isCorrect }, (imported.stations.first().tasks.single() as MultipleChoiceTask).options.map { it.isCorrect })
        assertEquals(setOf(StationMediaType.IMAGE, StationMediaType.AUDIO), imported.stations.first().media.map { it.mediaType }.toSet())
        assertTrue(
            original.stations.first().media.map { it.id }.toSet()
                .intersect(imported.stations.first().media.map { it.id }.toSet()).isEmpty()
        )
        imported.stations.first().media.forEach { media ->
            val importedFile = assetStore.resolve(imported.gameUuid, media.relativePath)
            assertTrue(importedFile.isFile)
            assertEquals(media.byteSize, importedFile.length())
            assertEquals(media.checksum, sha256(importedFile))
            val originalFile = when (media.mediaType) {
                StationMediaType.IMAGE -> image.finalFile
                StationMediaType.AUDIO -> audio.finalFile
            }
            assertArrayEquals(originalFile.readBytes(), importedFile.readBytes())
        }
        val qrLookup = repository.findStationByQrToken(imported.stations.first().qrToken)
        assertEquals(imported.id, qrLookup?.station?.gameId)
        assertEquals(listOf(sourceSession.id), repository.getActiveSessions().map { it.id })
        assertEquals(listOf(original.id), repository.getActiveSessions().map { it.gameId })
        assertEquals(original.id, repository.getSessionProgress(sourceSession.id)?.session?.gameId)
        assertTrue(repository.getCompletedSessionResults().isEmpty())

        val duplicatePreview = archiveService.inspectImport(Uri.fromFile(archiveFile))
        val duplicate = archiveService.confirmImport(duplicatePreview.token)
        gameUuids += duplicate.gameUuid
        assertNotEquals(imported.id, duplicate.id)
        assertNotEquals(imported.stations.first().qrToken, duplicate.stations.first().qrToken)
    }

    @Test
    fun versionTwoRoundTripPreservesEveryTaskTypeAndTheyCanBePlayed() {
        val mc = taskDraft()
        val truth = TrueFalseTaskDraft(UUID.randomUUID().toString(), "The earth is round", 1, true, 20)
        val text = ShortTextTaskDraft(UUID.randomUUID().toString(), "Capital of Slovakia", 2, "Bratislava", 30)
        val original = repository.createGame(GameDraft(
            "Typed game",
            listOf(StationDraft("Start", "", listOf(mc, truth, text)), StationDraft("End", "")))
        )
        gameUuids += original.gameUuid
        archiveService.exportGameToFile(original.id, archiveFile)
        val preview = archiveService.inspectImport(Uri.fromFile(archiveFile))
        val imported = archiveService.confirmImport(preview.token)
        gameUuids += imported.gameUuid
        val tasks = imported.stations.first().tasks
        assertEquals(listOf("MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_TEXT"), tasks.map { it.type.name })

        val session = repository.startOrResumeSession(imported.id).session
        repository.recordStationScan(session.id, imported.stations.first().qrToken)
        repository.savePendingTaskAnswer(session.id, tasks[0].id, TaskAnswer.Option((tasks[0] as MultipleChoiceTask).options.last().id))
        assertTrue((repository.submitTaskAttempt(session.id, tasks[0].id) as TaskSubmissionResult.Submitted).isCorrect)
        repository.savePendingTaskAnswer(session.id, tasks[1].id, TaskAnswer.BooleanValue(true))
        assertTrue((repository.submitTaskAttempt(session.id, tasks[1].id) as TaskSubmissionResult.Submitted).isCorrect)
        repository.savePendingTaskAnswer(session.id, tasks[2].id, TaskAnswer.TextValue("  BRATISLAVA "))
        val final = repository.submitTaskAttempt(session.id, tasks[2].id) as TaskSubmissionResult.Submitted
        assertTrue(final.isCorrect)
        assertEquals(425L, final.progress.score)
    }

    @Test
    fun versionThreeRoundTripRemapsBranchedStationReferencesAndCanBePlayed() {
        val task = taskDraft()
        val original = repository.createGame(GameDraft(
            "Branched trail",
            listOf(StationDraft("Question", "", listOf(task)), StationDraft("Information", "Read this"))
        ))
        gameUuids += original.gameUuid
        repository.updateGameRoute(original.id, GameRouteDraft(
            GameProgressionMode.BRANCHED,
            0,
            listOf(
                RouteTransitionDraft(0, RouteCondition.CORRECT, 1),
                RouteTransitionDraft(0, RouteCondition.INCORRECT, 1),
                RouteTransitionDraft(1, RouteCondition.INFO, null)
            )
        ))
        archiveService.exportGameToFile(original.id, archiveFile)
        val imported = archiveService.confirmImport(archiveService.inspectImport(Uri.fromFile(archiveFile)).token)
        gameUuids += imported.gameUuid
        assertEquals(GameProgressionMode.BRANCHED, imported.route.mode)
        assertEquals(imported.stations.first().id, imported.route.startStationId)
        assertTrue(imported.route.transitions.all { edge ->
            imported.stations.any { it.id == edge.sourceStationId } &&
                (edge.targetStationId == null || imported.stations.any { it.id == edge.targetStationId })
        })

        val session = repository.startOrResumeSession(imported.id)
        repository.recordStationScan(session.session.id, imported.stations.first().qrToken)
        val importedTask = imported.stations.first().tasks.single()
        val option = (importedTask as MultipleChoiceTask).options.first { it.isCorrect }
        repository.savePendingTaskAnswer(session.session.id, importedTask.id, TaskAnswer.Option(option.id))
        val answer = repository.submitTaskAttempt(session.session.id, importedTask.id) as TaskSubmissionResult.Submitted
        assertTrue(answer.isCorrect)
        assertEquals(imported.stations[1].id, answer.progress.nextStation?.id)
        val complete = repository.recordStationScan(session.session.id, imported.stations[1].qrToken) as StationScanResult.Accepted
        assertEquals(com.qrhry.app.domain.GameSessionStatus.COMPLETED, complete.progress.session.status)
    }

    @Test
    fun versionOneArchiveStillImportsTasksAsMultipleChoice() {
        val old = File(context.cacheDir, "qrhry-v1-${UUID.randomUUID()}.qrhry")
        val firstTask = JSONObject().put("prompt", "Legacy prompt").put("position", 0).put("points", 7)
            .put("options", JSONArray()
                .put(JSONObject().put("text", "A").put("position", 0).put("isCorrect", true))
                .put(JSONObject().put("text", "B").put("position", 1).put("isCorrect", false)))
        val stations = JSONArray()
            .put(JSONObject().put("title", "One").put("bodyText", "").put("position", 0)
                .put("media", JSONArray()).put("tasks", JSONArray().put(firstTask)))
            .put(JSONObject().put("title", "Two").put("bodyText", "").put("position", 1)
                .put("media", JSONArray()).put("tasks", JSONArray()))
        val manifest = JSONObject().put("format", "qrhry").put("formatVersion", 1).put("title", "Legacy")
            .put("scoringMode", "EVENTUAL_CORRECT").put("stations", stations).toString()
        writeZip(old, listOf(GameArchiveCodec.MANIFEST_ENTRY to manifest.toByteArray()))
        try {
            val preview = archiveService.inspectImport(Uri.fromFile(old))
            val imported = archiveService.confirmImport(preview.token)
            gameUuids += imported.gameUuid
            assertTrue(imported.stations.first().tasks.single() is MultipleChoiceTask)
            assertEquals("Legacy prompt", imported.stations.first().tasks.single().prompt)
        } finally {
            old.delete()
        }
    }

    @Test
    fun versionTwoArchiveImportsAsLinearGame() {
        val old = File(context.cacheDir, "qrhry-v2-${UUID.randomUUID()}.qrhry")
        val task = JSONObject().put("prompt", "Legacy typed task").put("position", 0)
            .put("points", 5).put("type", "TRUE_FALSE").put("correctAnswer", true)
        val stations = JSONArray()
            .put(JSONObject().put("title", "One").put("bodyText", "").put("position", 0)
                .put("media", JSONArray()).put("tasks", JSONArray().put(task)))
            .put(JSONObject().put("title", "Two").put("bodyText", "").put("position", 1)
                .put("media", JSONArray()).put("tasks", JSONArray()))
        val manifest = JSONObject().put("format", "qrhry").put("formatVersion", 2).put("title", "Legacy v2")
            .put("scoringMode", "EVENTUAL_CORRECT").put("stations", stations).toString()
        writeZip(old, listOf(GameArchiveCodec.MANIFEST_ENTRY to manifest.toByteArray()))
        try {
            val imported = archiveService.confirmImport(archiveService.inspectImport(Uri.fromFile(old)).token)
            gameUuids += imported.gameUuid
            assertEquals("Legacy v2", imported.title)
            assertEquals(com.qrhry.app.domain.GameProgressionMode.LINEAR, imported.route.mode)
            assertEquals("TRUE_FALSE", imported.stations.first().tasks.single().type.name)
        } finally {
            old.delete()
        }
    }

    @Test
    fun rejectsTraversalEntryAndUnsupportedManifestVersionWithoutKeepingStagingData() {
        listOf("../outside.bin", "/outside.bin", "C:/outside.bin", "assets/../outside.bin", "assets\\outside.bin")
            .forEach { unsafeName ->
                val traversalArchive = File(context.cacheDir, "qrhry-traversal-${UUID.randomUUID()}.qrhry")
                writeZip(
                    traversalArchive,
                    listOf(GameArchiveCodec.MANIFEST_ENTRY to "{}".toByteArray(), unsafeName to byteArrayOf(1))
                )
                val traversalStage = File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}")
                val traversalError = runCatching { GameArchiveCodec().read(traversalArchive, traversalStage) }.exceptionOrNull()
                assertTrue(traversalError is UserMessageException)
                assertEquals(UserMessageKey.ARCHIVE_INVALID, (traversalError as UserMessageException).messageKey)
                assertTrue(!traversalStage.exists())
                traversalArchive.delete()
            }

        val unsupportedArchive = File(context.cacheDir, "qrhry-version-${UUID.randomUUID()}.qrhry")
        ZipOutputStream(unsupportedArchive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(GameArchiveCodec.MANIFEST_ENTRY))
            zip.write(
                """{"format":"qrhry","formatVersion":99,"title":"Unsupported","scoringMode":"EVENTUAL_CORRECT","stations":[]}""".toByteArray()
            )
            zip.closeEntry()
        }
        val versionStage = File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}")
        val versionError = runCatching { GameArchiveCodec().read(unsupportedArchive, versionStage) }.exceptionOrNull()
        assertTrue(versionError is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_VERSION_UNSUPPORTED, (versionError as UserMessageException).messageKey)
        assertTrue(!versionStage.exists())
        unsupportedArchive.delete()
    }

    @Test
    fun rejectsMalformedManifestMissingMediaAndChecksumMismatch() {
        val codec = GameArchiveCodec()
        val malformedArchive = File(context.cacheDir, "qrhry-json-${UUID.randomUUID()}.qrhry")
        writeZip(malformedArchive, listOf(GameArchiveCodec.MANIFEST_ENTRY to "{".toByteArray()))
        val malformedError = runCatching {
            codec.read(malformedArchive, File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}"))
        }.exceptionOrNull()
        assertTrue(malformedError is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_INVALID, (malformedError as UserMessageException).messageKey)
        malformedArchive.delete()

        val mediaEntry = "assets/${UUID.randomUUID()}.bin"
        val manifestBytes = manifestWithMedia(mediaEntry).toByteArray()
        val missingArchive = File(context.cacheDir, "qrhry-missing-${UUID.randomUUID()}.qrhry")
        writeZip(missingArchive, listOf(GameArchiveCodec.MANIFEST_ENTRY to manifestBytes))
        val missingError = runCatching {
            codec.read(missingArchive, File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}"))
        }.exceptionOrNull()
        assertTrue(missingError is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_INVALID, (missingError as UserMessageException).messageKey)
        missingArchive.delete()

        val corruptArchive = File(context.cacheDir, "qrhry-corrupt-${UUID.randomUUID()}.qrhry")
        writeZip(
            corruptArchive,
            listOf(GameArchiveCodec.MANIFEST_ENTRY to manifestBytes, mediaEntry to byteArrayOf(1, 2, 3))
        )
        val corruptError = runCatching {
            codec.read(corruptArchive, File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}"))
        }.exceptionOrNull()
        assertTrue(corruptError is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_MEDIA_INVALID, (corruptError as UserMessageException).messageKey)
        corruptArchive.delete()
    }

    @Test
    fun rejectsArchiveOverCompressedSizeLimitBeforeReadingIt() {
        val oversized = File(context.cacheDir, "qrhry-large-${UUID.randomUUID()}.qrhry")
        java.io.RandomAccessFile(oversized, "rw").use {
            it.setLength(GameArchiveLimits.MAX_ARCHIVE_BYTES + 1)
        }
        val stage = File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}")
        val error = runCatching { GameArchiveCodec().read(oversized, stage) }.exceptionOrNull()
        assertTrue(error is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED, (error as UserMessageException).messageKey)
        assertTrue(!stage.exists())
        oversized.delete()
    }

    @Test
    fun rejectsManifestMediaSizeAbovePerEntryLimit() {
        val entry = "assets/${UUID.randomUUID()}.bin"
        val archive = File(context.cacheDir, "qrhry-entry-limit-${UUID.randomUUID()}.qrhry")
        writeZip(
            archive,
            listOf(
                GameArchiveCodec.MANIFEST_ENTRY to
                    manifestWithMedia(entry, GameArchiveLimits.MAX_ENTRY_BYTES + 1).toByteArray()
            )
        )
        val failure = runCatching {
            GameArchiveCodec().read(archive, File(context.cacheDir, "qrhry-stage-${UUID.randomUUID()}"))
        }.exceptionOrNull()
        assertTrue(failure is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED, (failure as UserMessageException).messageKey)
        archive.delete()
    }

    @Test
    fun databaseFailureAfterMediaPromotionLeavesNoPartialGameOrAssetDirectory() {
        val original = repository.createGame(
            GameDraft(
                "Rollback source",
                listOf(StationDraft("First", "Text"), StationDraft("Second", "Text"))
            )
        )
        gameUuids += original.gameUuid
        archiveService.exportGameToFile(original.id, archiveFile)
        databaseHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_archive_station_insert BEFORE INSERT ON stations
                BEGIN SELECT RAISE(ABORT, 'forced archive import failure'); END"""
        )
        val gameDirectoriesBefore = gameAssetDirectories()
        val preview = archiveService.inspectImport(Uri.fromFile(archiveFile))

        val failure = runCatching { archiveService.confirmImport(preview.token) }.exceptionOrNull()

        assertTrue(failure is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_IMPORT_FAILED, (failure as UserMessageException).messageKey)
        assertEquals(1, repository.getAllGames().size)
        assertEquals(gameDirectoriesBefore, gameAssetDirectories())
    }

    @Test
    fun cancellingImportPreviewRemovesPrivateStagingFiles() {
        val game = repository.createGame(
            GameDraft("Cancel preview", listOf(StationDraft("First", ""), StationDraft("Second", "")))
        )
        gameUuids += game.gameUuid
        archiveService.exportGameToFile(game.id, archiveFile)
        val preview = archiveService.inspectImport(Uri.fromFile(archiveFile))

        archiveService.cancelImport(preview.token)

        assertTrue(context.cacheDir.listFiles().orEmpty().none {
            it.name.startsWith("qrhry-import-${preview.token}")
        })
    }

    @Test
    fun exportRejectsMissingReferencedMediaWithoutLeavingArchive() {
        val game = repository.createGame(
            GameDraft(
                "Missing media source",
                listOf(StationDraft("First", "Image"), StationDraft("Second", "Text"))
            )
        )
        gameUuids += game.gameUuid
        val testResolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val asset = assetStore.stageImport(
            testResolver,
            Uri.parse("content://com.qrhry.app.asset-test/image"),
            game.gameUuid,
            StationMediaType.IMAGE,
            "fixture.png"
        )
        assetStore.promote(asset)
        repository.updateStationContent(
            stationId = game.stations.first().id,
            title = "First",
            bodyText = "Image",
            replacements = listOf(asset.toStationMedia(game.stations.first().id, StationMediaType.IMAGE))
        )
        asset.finalFile.delete()

        val failure = runCatching { archiveService.exportGameToFile(game.id, archiveFile) }.exceptionOrNull()

        assertTrue(failure is UserMessageException)
        assertEquals(UserMessageKey.ARCHIVE_MEDIA_INVALID, (failure as UserMessageException).messageKey)
        assertTrue(!archiveFile.exists())
    }

    private fun taskDraft() = MultipleChoiceTaskDraft(
        id = UUID.randomUUID().toString(),
        prompt = "Which path?",
        position = 0,
        points = 375,
        options = listOf(
            TaskOptionDraft(UUID.randomUUID().toString(), "Wrong", 0, false),
            TaskOptionDraft(UUID.randomUUID().toString(), "Right", 1, true)
        )
    )

    private fun manifestWithMedia(entryPath: String, byteSize: Long = 3): String {
        val media = JSONObject()
            .put("entryPath", entryPath)
            .put("mediaType", StationMediaType.IMAGE.name)
            .put("mimeType", "image/png")
            .put("originalFilename", JSONObject.NULL)
            .put("byteSize", byteSize)
            .put("sha256", "0".repeat(64))
        val firstStation = JSONObject()
            .put("title", "First")
            .put("bodyText", "Text")
            .put("position", 0)
            .put("stationKey", UUID.randomUUID().toString())
            .put("media", JSONArray().put(media))
            .put("tasks", JSONArray())
        val secondStation = JSONObject()
            .put("title", "Second")
            .put("bodyText", "Text")
            .put("position", 1)
            .put("stationKey", UUID.randomUUID().toString())
            .put("media", JSONArray())
            .put("tasks", JSONArray())
        return JSONObject()
            .put("format", GameArchiveCodec.FORMAT_NAME)
            .put("formatVersion", GameArchiveLimits.CURRENT_FORMAT_VERSION)
            .put("title", "Manifest test")
            .put("scoringMode", TaskScoringMode.EVENTUAL_CORRECT.name)
            .put("progressionMode", "LINEAR")
            .put("startStationKey", JSONObject.NULL)
            .put("transitions", JSONArray())
            .put("stations", JSONArray().put(firstStation).put(secondStation))
            .toString()
    }

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun ImportedAsset.toStationMedia(stationId: Long, mediaType: StationMediaType) =
        com.qrhry.app.domain.StationMedia(
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

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun gameAssetDirectories(): Set<String> =
        File(context.filesDir, "games").listFiles().orEmpty()
            .filter {
                it.isDirectory && runCatching { UUID.fromString(it.name).toString() == it.name }
                    .getOrDefault(false)
            }
            .map { it.name }
            .toSet()
}
