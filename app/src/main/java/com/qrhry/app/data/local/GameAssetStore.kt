package com.qrhry.app.data.local

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import com.qrhry.app.domain.StationMediaType
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

class GameAssetStore(private val filesDirectory: File) {
    fun stageImport(
        resolver: ContentResolver,
        source: Uri,
        gameUuid: String,
        mediaType: StationMediaType,
        originalFilename: String?
    ): ImportedAsset {
        require(isUuid(gameUuid)) { "Game identity is invalid." }
        val mimeType = resolver.getType(source)
            ?: throw IllegalArgumentException("The selected file type could not be determined.")
        require(isAllowedMimeType(mimeType, mediaType)) {
            "The selected file is not a supported ${mediaType.name.lowercase()}."
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
            } ?: throw IllegalArgumentException("The selected file could not be opened.")
            val byteSize = temporaryFile.length()
            require(byteSize > 0L) { "The selected file is empty." }
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
        require(asset.temporaryFile.parentFile == asset.finalFile.parentFile)
        check(asset.temporaryFile.renameTo(asset.finalFile)) {
            "The selected file could not be stored."
        }
    }

    fun discard(asset: ImportedAsset) {
        asset.temporaryFile.delete()
        asset.finalFile.delete()
    }

    fun resolve(gameUuid: String, relativePath: String): File {
        require(isUuid(gameUuid)) { "Game identity is invalid." }
        require(isSafeRelativePath(relativePath)) { "Stored media path is invalid." }
        val gameRoot = File(File(filesDirectory, "games"), gameUuid).canonicalFile
        val target = File(gameRoot, relativePath).canonicalFile
        require(target.path.startsWith(gameRoot.path + File.separator)) {
            "Stored media path escapes its game directory."
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
}