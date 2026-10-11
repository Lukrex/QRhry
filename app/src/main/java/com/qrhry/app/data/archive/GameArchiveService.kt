package com.qrhry.app.data.archive

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.qrhry.app.data.GameRepository
import com.qrhry.app.data.local.GameAssetStore
import com.qrhry.app.data.local.StagedGameAssets
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationMedia
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.TaskScoringMode
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class GameArchiveService(
    private val contentResolver: ContentResolver,
    private val cacheDirectory: File,
    private val repository: GameRepository,
    private val assetStore: GameAssetStore,
    private val codec: GameArchiveCodec = GameArchiveCodec()
) {
    private val stagedImports = linkedMapOf<String, StagedImport>()

    fun exportGameToUri(gameId: Long, target: Uri) {
        val temporaryArchive = File(cacheDirectory, "qrhry-export-${UUID.randomUUID()}.tmp")
        try {
            exportGameToFile(gameId, temporaryArchive)
            contentResolver.openOutputStream(target, "w")?.use { output ->
                FileInputStream(temporaryArchive).use { input -> copyLimited(input, output, GameArchiveLimits.MAX_ARCHIVE_BYTES) }
            } ?: throw UserMessageException(UserMessageKey.ARCHIVE_EXPORT_FAILED)
        } catch (exception: Exception) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_EXPORT_FAILED)
        } finally {
            temporaryArchive.delete()
        }
    }

    fun exportGameToFile(gameId: Long, archiveFile: File) {
        try {
            val game = repository.getGameExportSnapshot(gameId)
                ?: throw UserMessageException(UserMessageKey.GAME_UNAVAILABLE)
            val mediaFiles = linkedMapOf<String, File>()
            val stations = game.stations.map { station ->
                val media = station.media.map { item ->
                    val source = assetStore.resolve(game.gameUuid, item.relativePath)
                    if (!source.isFile || source.length() != item.byteSize || sha256(source) != item.checksum) {
                        throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)
                    }
                    val entryPath = "assets/${UUID.randomUUID()}.bin"
                    mediaFiles[entryPath] = source
                    GameArchiveMedia(
                        entryPath = entryPath,
                        mediaType = item.mediaType,
                        mimeType = item.mimeType,
                        originalFilename = item.originalFilename,
                        byteSize = item.byteSize,
                        checksum = item.checksum
                    )
                }
                GameArchiveStation(
                    title = station.title,
                    bodyText = station.bodyText,
                    position = station.position,
                    media = media,
                    tasks = station.tasks.map { task ->
                        GameArchiveTask(
                            prompt = task.prompt,
                            position = task.position,
                            points = task.points,
                            options = task.options.map { option ->
                                GameArchiveOption(option.text, option.position, option.isCorrect)
                            }
                        )
                    }
                )
            }
            codec.write(
                GameArchiveBundle(
                    manifest = GameArchiveManifest(
                        formatVersion = GameArchiveLimits.CURRENT_FORMAT_VERSION,
                        title = game.title,
                        scoringMode = game.scoringMode,
                        stations = stations
                    ),
                    mediaFiles = mediaFiles
                ),
                archiveFile
            )
        } catch (exception: Exception) {
            archiveFile.delete()
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_EXPORT_FAILED)
        }
    }

    @Synchronized
    fun inspectImport(source: Uri): GameArchiveImportPreview {
        val token = UUID.randomUUID().toString()
        val archiveFile = File(cacheDirectory, "qrhry-import-$token.qrhry")
        val stagingDirectory = File(cacheDirectory, "qrhry-import-$token")
        try {
            cacheDirectory.mkdirs()
            val input = contentResolver.openInputStream(source)
                ?: throw UserMessageException(UserMessageKey.ARCHIVE_INVALID)
            input.use { stream ->
                FileOutputStream(archiveFile).use { output ->
                    copyLimited(stream, output, GameArchiveLimits.MAX_ARCHIVE_BYTES)
                }
            }
            val bundle = codec.read(archiveFile, stagingDirectory)
            val preview = GameArchiveImportPreview(
                token = token,
                title = bundle.manifest.title,
                scoringMode = bundle.manifest.scoringMode,
                stationCount = bundle.manifest.stations.size,
                taskCount = bundle.manifest.stations.sumOf { it.tasks.size },
                mediaCount = bundle.manifest.stations.sumOf { it.media.size }
            )
            stagedImports[token] = StagedImport(archiveFile, stagingDirectory, bundle)
            return preview
        } catch (exception: Exception) {
            archiveFile.delete()
            stagingDirectory.deleteRecursively()
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_INVALID)
        }
    }

    @Synchronized
    fun cancelImport(token: String) {
        stagedImports.remove(token)?.cleanup()
    }

    @Synchronized
    fun confirmImport(token: String): Game {
        val stagedImport = stagedImports.remove(token)
            ?: throw UserMessageException(UserMessageKey.ARCHIVE_IMPORT_FAILED)
        var stagedAssets: StagedGameAssets? = null
        try {
            val gameUuid = UUID.randomUUID().toString()
            stagedAssets = assetStore.stageArchiveAssets(stagedImport.bundle, gameUuid)
            assetStore.promoteArchiveAssets(stagedAssets)
            val draft = stagedImport.bundle.manifest.toGameDraft()
            return repository.importGame(draft, gameUuid, stagedAssets.mediaByStation)
        } catch (exception: Exception) {
            stagedAssets?.let(assetStore::discardArchiveAssets)
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_IMPORT_FAILED)
        } finally {
            stagedImport.cleanup()
        }
    }

    fun cleanupOrphanedImports() {
        val staleBefore = System.currentTimeMillis() - STALE_IMPORT_AGE_MILLIS
        cacheDirectory.listFiles()
            ?.filter { it.name.startsWith("qrhry-import-") && it.lastModified() < staleBefore }
            ?.forEach(File::deleteRecursively)
    }

    private fun GameArchiveManifest.toGameDraft() = GameDraft(
        title = title,
        scoringMode = scoringMode,
        stations = stations.map { station ->
            StationDraft(
                title = station.title,
                bodyText = station.bodyText,
                tasks = station.tasks.map { task ->
                    MultipleChoiceTaskDraft(
                        id = UUID.randomUUID().toString(),
                        prompt = task.prompt,
                        position = task.position,
                        points = task.points,
                        options = task.options.map { option ->
                            TaskOptionDraft(
                                id = UUID.randomUUID().toString(),
                                text = option.text,
                                position = option.position,
                                isCorrect = option.isCorrect
                            )
                        }
                    )
                }
            )
        }
    )

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > maxBytes) throw UserMessageException(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED)
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private data class StagedImport(
        val archiveFile: File,
        val stagingDirectory: File,
        val bundle: GameArchiveBundle
    ) {
        fun cleanup() {
            archiveFile.delete()
            stagingDirectory.deleteRecursively()
        }
    }

    companion object {
        private const val STALE_IMPORT_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}

data class GameArchiveImportPreview(
    val token: String,
    val title: String,
    val scoringMode: TaskScoringMode,
    val stationCount: Int,
    val taskCount: Int,
    val mediaCount: Int
)
