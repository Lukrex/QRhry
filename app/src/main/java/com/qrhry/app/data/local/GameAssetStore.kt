package com.qrhry.app.data.local

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.StationMedia
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import com.qrhry.app.data.archive.GameArchiveBundle
import com.qrhry.app.data.archive.GameArchiveLimits
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedAsset(
    val mediaId: String,
    val relativePath: String,
    val mimeType: String,
    val originalFilename: String?,
    val checksum: String,
    val byteSize: Long,
    val temporaryFile: File,
    val finalFile: File
)

data class StagedGameAssets(
    val gameUuid: String,
    val stagingDirectory: File,
    val finalDirectory: File,
    val mediaByStation: List<List<StationMedia>>
)

class GameAssetStore(private val filesDirectory: File) {
    fun stageArchiveAssets(bundle: GameArchiveBundle, gameUuid: String): StagedGameAssets {
        if (!isUuid(gameUuid)) throw UserMessageException(UserMessageKey.MEDIA_GAME_ID_INVALID)
        val gamesDirectory = File(filesDirectory, "games").canonicalFile
        if (!gamesDirectory.exists() && !gamesDirectory.mkdirs()) {
            throw UserMessageException(UserMessageKey.MEDIA_STORE_FAILED)
        }
        val finalDirectory = File(gamesDirectory, gameUuid).canonicalFile
        val stagingDirectory = File(gamesDirectory, ".qrhry-import-$gameUuid-${UUID.randomUUID()}")
            .canonicalFile
        if (!finalDirectory.path.startsWith(gamesDirectory.path + File.separator) ||
            finalDirectory.exists() ||
            !stagingDirectory.path.startsWith(gamesDirectory.path + File.separator) ||
            !stagingDirectory.mkdirs()
        ) throw UserMessageException(UserMessageKey.MEDIA_STORE_FAILED)

        try {
            val mediaByStation = bundle.manifest.stations.map { station ->
                station.media.map { media ->
                    val source = bundle.mediaFiles[media.entryPath]
                        ?.takeIf(File::isFile)
                        ?: throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)
                    val mediaId = UUID.randomUUID().toString()
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(media.mimeType)
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
                        ?: "bin"
                    val relativePath = "assets/$mediaId.$extension"
                    val target = File(stagingDirectory, relativePath).canonicalFile
                    if (!target.path.startsWith(stagingDirectory.path + File.separator)) {
                        throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)
                    }
                    target.parentFile?.mkdirs()
                    val copied = copyAndHash(source, target, media.byteSize)
                    if (copied.first != media.byteSize || copied.second != media.checksum) {
                        throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)
                    }
                    StationMedia(
                        id = mediaId,
                        stationId = 0L,
                        mediaType = media.mediaType,
                        relativePath = relativePath,
                        mimeType = media.mimeType,
                        originalFilename = media.originalFilename,
                        checksum = copied.second,
                        byteSize = copied.first,
                        displayOrder = if (media.mediaType == StationMediaType.IMAGE) 0 else 1
                    )
                }
            }
            return StagedGameAssets(gameUuid, stagingDirectory, finalDirectory, mediaByStation)
        } catch (exception: Exception) {
            stagingDirectory.deleteRecursively()
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)
        }
    }

    fun promoteArchiveAssets(staged: StagedGameAssets) {
        if (staged.finalDirectory.exists() || !staged.stagingDirectory.renameTo(staged.finalDirectory)) {
            throw UserMessageException(UserMessageKey.MEDIA_STORE_FAILED)
        }
    }

    fun discardArchiveAssets(staged: StagedGameAssets) {
        staged.stagingDirectory.deleteRecursively()
        staged.finalDirectory.deleteRecursively()
    }

    fun cleanupOrphanedGameDirectories(existingGameUuids: Set<String>) {
        val gamesDirectory = File(filesDirectory, "games")
        val staleBefore = System.currentTimeMillis() - STALE_IMPORT_AGE_MILLIS
        gamesDirectory.listFiles()?.forEach { child ->
            if (child.lastModified() < staleBefore &&
                (child.name.startsWith(".qrhry-import-") ||
                    (isUuid(child.name) && child.name !in existingGameUuids))
            ) child.deleteRecursively()
        }
    }

    fun stageImport(
        resolver: ContentResolver,
        source: Uri,
        gameUuid: String,
        mediaType: StationMediaType,
        originalFilename: String?
    ): ImportedAsset {
        if (!isUuid(gameUuid)) throw UserMessageException(UserMessageKey.MEDIA_GAME_ID_INVALID)
        val mimeType = resolver.getType(source)
            ?: throw UserMessageException(UserMessageKey.MEDIA_TYPE_UNKNOWN)
        if (!isAllowedMimeType(mimeType, mediaType)) {
            throw UserMessageException(UserMessageKey.MEDIA_TYPE_UNSUPPORTED)
        }
        val mediaId = UUID.randomUUID().toString()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
            ?: "bin"
        val relativePath = "assets/$mediaId.$extension"
        val finalFile = resolve(gameUuid, relativePath)
        val temporaryFile = File(finalFile.parentFile, "$mediaId.tmp")
        finalFile.parentFile?.mkdirs()

        try {
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(temporaryFile).use { output -> input.copyTo(output) }
            } ?: throw UserMessageException(UserMessageKey.MEDIA_OPEN_FAILED)
            val byteSize = temporaryFile.length()
            if (byteSize <= 0L) throw UserMessageException(UserMessageKey.MEDIA_EMPTY)
            val checksum = FileInputStream(temporaryFile).use(::sha256)
            return ImportedAsset(
                mediaId = mediaId,
                relativePath = relativePath,
                mimeType = mimeType,
                originalFilename = originalFilename?.take(255),
                checksum = checksum,
                byteSize = byteSize,
                temporaryFile = temporaryFile,
                finalFile = finalFile
            )
        } catch (exception: Exception) {
            temporaryFile.delete()
            throw exception
        }
    }

    fun promote(asset: ImportedAsset) {
        if (asset.temporaryFile.parentFile != asset.finalFile.parentFile ||
            !asset.temporaryFile.renameTo(asset.finalFile)
        ) throw UserMessageException(UserMessageKey.MEDIA_STORE_FAILED)
    }

    fun discard(asset: ImportedAsset) {
        asset.temporaryFile.delete()
        asset.finalFile.delete()
    }

    fun resolve(gameUuid: String, relativePath: String): File {
        if (!isUuid(gameUuid)) throw UserMessageException(UserMessageKey.MEDIA_GAME_ID_INVALID)
        if (!isSafeRelativePath(relativePath)) throw UserMessageException(UserMessageKey.MEDIA_PATH_INVALID)
        val gameRoot = File(File(filesDirectory, "games"), gameUuid).canonicalFile
        val target = File(gameRoot, relativePath).canonicalFile
        if (!target.path.startsWith(gameRoot.path + File.separator)) {
            throw UserMessageException(UserMessageKey.MEDIA_PATH_INVALID)
        }
        return target
    }

    fun delete(gameUuid: String, relativePath: String): Boolean =
        runCatching { resolve(gameUuid, relativePath).delete() }.getOrDefault(false)

    fun fileExists(gameUuid: String, relativePath: String): Boolean =
        runCatching { resolve(gameUuid, relativePath).isFile }.getOrDefault(false)

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
            path.startsWith("assets/")

    private fun isAllowedMimeType(mimeType: String, mediaType: StationMediaType): Boolean = when (mediaType) {
        StationMediaType.IMAGE -> mimeType.startsWith("image/")
        StationMediaType.AUDIO -> mimeType.startsWith("audio/")
    }

    private fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private fun sha256(input: FileInputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAndHash(source: File, target: File, maxBytes: Long): Pair<Long, String> {
        if (source.length() > maxBytes || maxBytes > GameArchiveLimits.MAX_ENTRY_BYTES) {
            throw UserMessageException(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    byteSize += read
                    if (byteSize > maxBytes || byteSize > GameArchiveLimits.MAX_ENTRY_BYTES) {
                        throw UserMessageException(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED)
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return byteSize to digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val STALE_IMPORT_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
