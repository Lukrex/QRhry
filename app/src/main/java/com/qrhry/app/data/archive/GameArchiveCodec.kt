package com.qrhry.app.data.archive

import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.StationTaskType
import com.qrhry.app.domain.TaskScoringMode
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import com.qrhry.app.domain.GameProgressionMode
import com.qrhry.app.domain.GameRouteDraft
import com.qrhry.app.domain.GameRouteValidator
import com.qrhry.app.domain.RouteCondition
import com.qrhry.app.domain.RouteTransitionDraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Enumeration
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class GameArchiveCodec {
    fun read(archiveFile: File, stagingDirectory: File): GameArchiveBundle {
        var ownsStagingDirectory = false
        try {
            if (!archiveFile.isFile) invalidArchive()
            if (archiveFile.length() > GameArchiveLimits.MAX_ARCHIVE_BYTES) limitExceeded()
            if (stagingDirectory.exists() || !stagingDirectory.mkdirs()) invalidArchive()
            ownsStagingDirectory = true

            val mediaFiles = linkedMapOf<String, File>()
            val manifest = ZipFile(archiveFile).use { zip ->
                val entries = zip.entries().toListChecked()
                val entriesByName = linkedMapOf<String, ZipEntry>()
                entries.forEach { entry ->
                    if (entry.isDirectory || !isAllowedEntryName(entry.name) || entriesByName.put(entry.name, entry) != null) {
                        invalidArchive()
                    }
                }
                val manifestEntry = entriesByName[MANIFEST_ENTRY] ?: invalidArchive()
                val manifestBytes = zip.getInputStream(manifestEntry).use {
                    readBounded(it, GameArchiveLimits.MAX_MANIFEST_BYTES)
                }
                val parsed = parseManifest(decodeUtf8(manifestBytes))
                val declaredPaths = parsed.stations.flatMap { it.media }.map { it.entryPath }.toSet()
                val actualPaths = entriesByName.keys - MANIFEST_ENTRY
                if (declaredPaths.size != parsed.stations.sumOf { it.media.size } || declaredPaths != actualPaths) {
                    invalidArchive()
                }

                var totalBytes = manifestBytes.size.toLong()
                parsed.stations.flatMap { it.media }.forEachIndexed { assetIndex, media ->
                    val target = File(stagingDirectory, "asset-$assetIndex.tmp")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val copied = zip.getInputStream(entriesByName.getValue(media.entryPath)).use { input ->
                        FileOutputStream(target).use { output ->
                            copyBounded(
                                input = input,
                                output = output,
                                maxBytes = minOf(media.byteSize, GameArchiveLimits.MAX_ENTRY_BYTES),
                                digest = digest
                            )
                        }
                    }
                    if (copied != media.byteSize || digest.toHex() != media.checksum) {
                        mediaInvalid()
                    }
                    totalBytes += copied
                    if (totalBytes > GameArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) limitExceeded()
                    mediaFiles[media.entryPath] = target
                }
                parsed
            }
            return GameArchiveBundle(manifest, mediaFiles)
        } catch (exception: Exception) {
            if (ownsStagingDirectory) stagingDirectory.deleteRecursively()
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_INVALID)
        }
    }

    fun write(bundle: GameArchiveBundle, archiveFile: File) {
        try {
            if (bundle.manifest.formatVersion != GameArchiveLimits.CURRENT_FORMAT_VERSION) {
                throw UserMessageException(UserMessageKey.ARCHIVE_VERSION_UNSUPPORTED)
            }
            validateManifest(bundle.manifest)
            val descriptors = bundle.manifest.stations.flatMap { it.media }
            if (descriptors.map { it.entryPath }.toSet() != bundle.mediaFiles.keys) mediaInvalid()
            archiveFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException() }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zip ->
                val manifestBytes = encodeManifest(bundle.manifest).toByteArray(StandardCharsets.UTF_8)
                if (manifestBytes.size > GameArchiveLimits.MAX_MANIFEST_BYTES) limitExceeded()
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestBytes)
                zip.closeEntry()
                var totalBytes = manifestBytes.size.toLong()
                descriptors.forEach { media ->
                    val file = bundle.mediaFiles[media.entryPath]?.takeIf(File::isFile) ?: mediaInvalid()
                    if (file.length() > GameArchiveLimits.MAX_ENTRY_BYTES) limitExceeded()
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(media.entryPath))
                    val copied = FileInputStream(file).use { input ->
                        copyBounded(
                            input = input,
                            output = zip,
                            maxBytes = minOf(media.byteSize, GameArchiveLimits.MAX_ENTRY_BYTES),
                            digest = digest
                        )
                    }
                    zip.closeEntry()
                    if (copied != media.byteSize || digest.toHex() != media.checksum) mediaInvalid()
                    totalBytes += copied
                    if (totalBytes > GameArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) limitExceeded()
                }
            }
            if (archiveFile.length() > GameArchiveLimits.MAX_ARCHIVE_BYTES) limitExceeded()
        } catch (exception: Exception) {
            archiveFile.delete()
            if (exception is UserMessageException) throw exception
            throw UserMessageException(UserMessageKey.ARCHIVE_EXPORT_FAILED)
        }
    }

    private fun parseManifest(json: String): GameArchiveManifest {
        validateJsonNesting(json)
        val rawRoot = JSONObject(json)
        val version = rawRoot.requiredInt("formatVersion")
        if (version !in 1..GameArchiveLimits.CURRENT_FORMAT_VERSION) {
            throw UserMessageException(UserMessageKey.ARCHIVE_VERSION_UNSUPPORTED)
        }
        val root = rawRoot.requireKeys(*(
            if (version >= 3) setOf("format", "formatVersion", "title", "scoringMode", "stations", "progressionMode", "startStationKey", "transitions")
            else setOf("format", "formatVersion", "title", "scoringMode", "stations")
        ).toTypedArray())
        if (root.requiredString("format") != FORMAT_NAME) invalidArchive()
        val manifest = GameArchiveManifest(
            formatVersion = version,
            title = root.requiredString("title"),
            scoringMode = root.requiredEnum("scoringMode", TaskScoringMode.entries.toTypedArray()),
            stations = root.requiredArray("stations", GameArchiveLimits.MAX_STATIONS).mapIndexed { index, value ->
                val station = (value as? JSONObject)?.requireKeys(*(
                    if (version >= 3) setOf("title", "bodyText", "position", "media", "tasks", "stationKey")
                    else setOf("title", "bodyText", "position", "media", "tasks")
                ).toTypedArray())
                    ?: invalidArchive()
                GameArchiveStation(
                    title = station.requiredString("title"),
                    bodyText = station.requiredString("bodyText"),
                    position = station.requiredInt("position"),
                    media = station.requiredArray("media", GameArchiveLimits.MAX_MEDIA_PER_STATION).map { mediaValue ->
                        val media = (mediaValue as? JSONObject)?.requireKeys(
                            "entryPath", "mediaType", "mimeType", "originalFilename", "byteSize", "sha256"
                        ) ?: invalidArchive()
                        GameArchiveMedia(
                            entryPath = media.requiredString("entryPath"),
                            mediaType = media.requiredEnum("mediaType", StationMediaType.entries.toTypedArray()),
                            mimeType = media.requiredString("mimeType"),
                            originalFilename = media.optionalString("originalFilename"),
                            byteSize = media.requiredLong("byteSize"),
                            checksum = media.requiredString("sha256")
                        )
                    },
                    tasks = station.requiredArray("tasks", GameArchiveLimits.MAX_TASKS_PER_STATION).map { taskValue ->
                        val rawTask = taskValue as? JSONObject ?: invalidArchive()
                        val task = if (version == 1) rawTask.requireKeys("prompt", "position", "points", "options")
                        else rawTask
                        val type = if (version == 1) StationTaskType.MULTIPLE_CHOICE
                        else task.requiredEnum("type", StationTaskType.entries.toTypedArray())
                        val timerKey = if (version >= 4) setOf("timeLimitSeconds") else emptySet()
                        val expectedKeys = when (type) {
                            StationTaskType.MULTIPLE_CHOICE -> (if (version == 1) setOf("prompt", "position", "points", "options") else setOf("prompt", "position", "points", "type", "options")) + timerKey
                            StationTaskType.TRUE_FALSE -> setOf("prompt", "position", "points", "type", "correctAnswer") + timerKey
                            StationTaskType.SHORT_TEXT -> setOf("prompt", "position", "points", "type", "correctAnswer") + timerKey
                        }
                        task.requireKeys(*expectedKeys.toTypedArray())
                        val options = if (type == StationTaskType.MULTIPLE_CHOICE) task.requiredArray("options", GameArchiveLimits.MAX_OPTIONS_PER_TASK).map { optionValue ->
                                val option = (optionValue as? JSONObject)?.requireKeys("text", "position", "isCorrect")
                                    ?: invalidArchive()
                                GameArchiveOption(
                                    text = option.requiredString("text"),
                                    position = option.requiredInt("position"),
                                    isCorrect = option.requiredBoolean("isCorrect")
                                )
                            } else emptyList()
                        val answer = when (type) {
                            StationTaskType.TRUE_FALSE -> task.requiredBoolean("correctAnswer")
                            StationTaskType.SHORT_TEXT -> task.requiredString("correctAnswer")
                            else -> null
                        }
                        GameArchiveTask(
                            prompt = task.requiredString("prompt"),
                            position = task.requiredInt("position"),
                            points = task.requiredInt("points"),
                            type = type,
                            options = options,
                            correctBoolean = answer as? Boolean,
                            correctText = answer as? String,
                            timeLimitSeconds = if (version >= 4 && !task.isNull("timeLimitSeconds")) task.requiredInt("timeLimitSeconds") else null
                        )
                    },
                    stationKey = if (version >= 3) station.requiredString("stationKey") else ""
                ).also { if (it.position != index) invalidArchive() }
            },
            progressionMode = if (version >= 3) root.requiredEnum("progressionMode", GameProgressionMode.entries.toTypedArray()) else GameProgressionMode.LINEAR,
            startStationKey = if (version >= 3 && !root.isNull("startStationKey")) root.requiredString("startStationKey") else null,
            transitions = if (version >= 3) root.requiredArray("transitions", GameArchiveLimits.MAX_STATIONS * 2).map { value ->
                val transition = (value as? JSONObject)?.requireKeys("sourceStationKey", "condition", "targetStationKey") ?: invalidArchive()
                GameArchiveTransition(
                    transition.requiredString("sourceStationKey"),
                    transition.requiredEnum("condition", RouteCondition.entries.toTypedArray()),
                    if (transition.isNull("targetStationKey")) null else transition.requiredString("targetStationKey")
                )
            } else emptyList()
        )
        validateManifest(manifest)
        return manifest
    }

    private fun validateManifest(manifest: GameArchiveManifest) {
        if (manifest.formatVersion !in 1..GameArchiveLimits.CURRENT_FORMAT_VERSION) {
            throw UserMessageException(UserMessageKey.ARCHIVE_VERSION_UNSUPPORTED)
        }
        if (manifest.title.isBlank() || manifest.title.length > MAX_TEXT_LENGTH) invalidArchive()
        if (manifest.stations.size !in 2..GameArchiveLimits.MAX_STATIONS) limitExceeded()
        if (manifest.formatVersion >= 3) {
            val stationKeys = manifest.stations.map { it.stationKey }
            if (stationKeys.any { !isCanonicalUuid(it) } || stationKeys.size != stationKeys.toSet().size) invalidArchive()
            if (manifest.progressionMode == GameProgressionMode.LINEAR) {
                if (manifest.startStationKey != null || manifest.transitions.isNotEmpty()) invalidArchive()
            } else {
                val indexes = stationKeys.withIndex().associate { it.value to it.index }
                val startPosition = manifest.startStationKey?.let(indexes::get) ?: invalidArchive()
                if (manifest.transitions.any { it.sourceStationKey !in indexes || (it.targetStationKey != null && it.targetStationKey !in indexes) }) invalidArchive()
                val route = GameRouteDraft(
                    GameProgressionMode.BRANCHED,
                    startPosition,
                    manifest.transitions.map { RouteTransitionDraft(indexes.getValue(it.sourceStationKey), it.condition, it.targetStationKey?.let(indexes::getValue)) }
                )
                val validation = GameRouteValidator.validationError(
                    manifest.stations.map { GameRouteValidator.Node(it.position, it.tasks.size) }, route
                )
                if (validation != null) invalidArchive()
            }
        }
        var taskCount = 0
        val mediaPaths = mutableSetOf<String>()
        manifest.stations.forEachIndexed { stationIndex, station ->
            if (station.position != stationIndex || station.title.isBlank() || station.title.length > MAX_TEXT_LENGTH ||
                station.bodyText.length > MAX_TEXT_LENGTH
            ) invalidArchive()
            if (station.media.size > GameArchiveLimits.MAX_MEDIA_PER_STATION) limitExceeded()
            if (station.media.map { it.mediaType }.toSet().size != station.media.size) invalidArchive()
            station.media.forEach { media ->
                if (!isPortableAssetPath(media.entryPath) || !mediaPaths.add(media.entryPath)) invalidArchive()
                if (media.byteSize !in 1..GameArchiveLimits.MAX_ENTRY_BYTES) limitExceeded()
                if (!SHA256_PATTERN.matches(media.checksum)) mediaInvalid()
                if (media.originalFilename != null && media.originalFilename.length > MAX_FILENAME_LENGTH) invalidArchive()
                val expectedPrefix = if (media.mediaType == StationMediaType.IMAGE) "image/" else "audio/"
                if (!media.mimeType.startsWith(expectedPrefix) ||
                    media.mimeType.length > MAX_MIME_LENGTH ||
                    !MIME_PATTERN.matches(media.mimeType)
                ) mediaInvalid()
            }
            if (station.tasks.size > GameArchiveLimits.MAX_TASKS_PER_STATION) limitExceeded()
            taskCount += station.tasks.size
            station.tasks.forEachIndexed { taskIndex, task ->
                if (task.position != taskIndex || task.prompt.isBlank() || task.prompt.length > MAX_TEXT_LENGTH || task.points < 0 ||
                    !com.qrhry.app.domain.TaskTimerLimits.isValid(task.timeLimitSeconds)
                ) {
                    invalidArchive()
                }
                when (task.type) {
                    StationTaskType.MULTIPLE_CHOICE -> {
                        if (task.options.size !in 2..GameArchiveLimits.MAX_OPTIONS_PER_TASK) limitExceeded()
                        if (task.correctBoolean != null || task.correctText != null ||
                            task.options.map { it.position } != task.options.indices.toList() ||
                            task.options.any { it.text.isBlank() || it.text.length > MAX_TEXT_LENGTH } ||
                            task.options.count { it.isCorrect } != 1
                        ) invalidArchive()
                    }
                    StationTaskType.TRUE_FALSE -> if (task.correctBoolean == null || task.correctText != null || task.options.isNotEmpty()) invalidArchive()
                    StationTaskType.SHORT_TEXT -> if (task.correctText.isNullOrBlank() || task.correctBoolean != null || task.options.isNotEmpty()) invalidArchive()
                }
            }
        }
        if (taskCount > MAX_TOTAL_TASKS) limitExceeded()
        if (mediaPaths.size + 1 > GameArchiveLimits.MAX_ENTRIES) limitExceeded()
    }

    private fun encodeManifest(manifest: GameArchiveManifest): String {
        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", manifest.formatVersion)
            .put("title", manifest.title)
            .put("scoringMode", manifest.scoringMode.name)
        if (manifest.formatVersion >= 3) {
            root.put("progressionMode", manifest.progressionMode.name)
                .put("startStationKey", manifest.startStationKey ?: JSONObject.NULL)
            val transitions = JSONArray()
            manifest.transitions.forEach { transition ->
                transitions.put(JSONObject()
                    .put("sourceStationKey", transition.sourceStationKey)
                    .put("condition", transition.condition.name)
                    .put("targetStationKey", transition.targetStationKey ?: JSONObject.NULL))
            }
            root.put("transitions", transitions)
        }
        val stations = JSONArray()
        manifest.stations.forEach { station ->
            val media = JSONArray()
            station.media.forEach { item ->
                media.put(
                    JSONObject()
                        .put("entryPath", item.entryPath)
                        .put("mediaType", item.mediaType.name)
                        .put("mimeType", item.mimeType)
                        .put("originalFilename", item.originalFilename ?: JSONObject.NULL)
                        .put("byteSize", item.byteSize)
                        .put("sha256", item.checksum)
                )
            }
            val tasks = JSONArray()
            station.tasks.forEach { task ->
                val options = JSONArray()
                task.options.forEach { option ->
                    options.put(
                        JSONObject()
                            .put("text", option.text)
                            .put("position", option.position)
                            .put("isCorrect", option.isCorrect)
                    )
                }
                val encoded = JSONObject()
                        .put("prompt", task.prompt)
                        .put("position", task.position)
                        .put("points", task.points)
                        .put("type", task.type.name)
                if (manifest.formatVersion >= 4) encoded.put("timeLimitSeconds", task.timeLimitSeconds ?: JSONObject.NULL)
                when (task.type) {
                    StationTaskType.MULTIPLE_CHOICE -> encoded.put("options", options)
                    StationTaskType.TRUE_FALSE -> encoded.put("correctAnswer", task.correctBoolean)
                    StationTaskType.SHORT_TEXT -> encoded.put("correctAnswer", task.correctText)
                }
                tasks.put(encoded)
            }
            stations.put(
                JSONObject()
                    .put("title", station.title)
                    .put("bodyText", station.bodyText)
                    .put("position", station.position)
                    .put("media", media)
                    .put("tasks", tasks)
                    .also { if (manifest.formatVersion >= 3) it.put("stationKey", station.stationKey) }
            )
        }
        return root.put("stations", stations).toString()
    }

    private fun JSONObject.requireKeys(vararg expected: String): JSONObject {
        val actual = keys().asSequence().toSet()
        if (actual != expected.toSet()) invalidArchive()
        return this
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private fun JSONObject.requiredString(key: String): String =
        (get(key) as? String)?.takeIf { it.length <= MAX_TEXT_LENGTH } ?: invalidArchive()

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key)) invalidArchive()
        if (isNull(key)) return null
        return (get(key) as? String)?.takeIf { it.length <= MAX_FILENAME_LENGTH } ?: invalidArchive()
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean = get(key) as? Boolean ?: invalidArchive()

    private fun JSONObject.requiredInt(key: String): Int {
        val value = get(key) as? Number ?: invalidArchive()
        val longValue = value.toLong()
        if (value.toDouble() != longValue.toDouble() || longValue !in Int.MIN_VALUE..Int.MAX_VALUE) invalidArchive()
        return longValue.toInt()
    }

    private fun JSONObject.requiredLong(key: String): Long {
        val value = get(key) as? Number ?: invalidArchive()
        val longValue = value.toLong()
        if (value.toDouble() != longValue.toDouble()) invalidArchive()
        return longValue
    }

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String, values: Array<T>): T {
        val raw = requiredString(key)
        return values.firstOrNull { it.name == raw } ?: invalidArchive()
    }

    private fun JSONObject.requiredArray(key: String, maximum: Int): List<Any> {
        val array = get(key) as? JSONArray ?: invalidArchive()
        if (array.length() > maximum) limitExceeded()
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(array.get(index))
        }
    }

    private fun isAllowedEntryName(name: String): Boolean =
        name == MANIFEST_ENTRY || isPortableAssetPath(name)

    private fun isPortableAssetPath(name: String): Boolean =
        name.matches(ASSET_PATH_PATTERN) && runCatching {
            val uuid = name.removePrefix("assets/").removeSuffix(".bin")
            UUID.fromString(uuid).toString() == uuid
        }.getOrDefault(false)

    private fun Enumeration<out ZipEntry>.toListChecked(): List<ZipEntry> {
        val result = mutableListOf<ZipEntry>()
        while (hasMoreElements()) {
            if (result.size >= GameArchiveLimits.MAX_ENTRIES) limitExceeded()
            result += nextElement()
        }
        return result
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > maxBytes) limitExceeded()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long, digest: MessageDigest): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > maxBytes) limitExceeded()
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return count
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        invalidArchive()
    }

    private fun validateJsonNesting(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_NESTING) invalidArchive()
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) invalidArchive()
                    }
                }
            }
        }
        if (inString || depth != 0) invalidArchive()
    }

    private fun MessageDigest.toHex(): String = digest().joinToString("") { "%02x".format(it) }

    private fun invalidArchive(): Nothing = throw UserMessageException(UserMessageKey.ARCHIVE_INVALID)
    private fun limitExceeded(): Nothing = throw UserMessageException(UserMessageKey.ARCHIVE_LIMIT_EXCEEDED)
    private fun mediaInvalid(): Nothing = throw UserMessageException(UserMessageKey.ARCHIVE_MEDIA_INVALID)

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val FORMAT_NAME = "qrhry"
        private val MAX_TEXT_LENGTH = GameArchiveLimits.MAX_MANIFEST_BYTES.toInt()
        private const val MAX_FILENAME_LENGTH = 255
        private const val MAX_MIME_LENGTH = 128
        private const val MAX_TOTAL_TASKS = 1024
        private const val MAX_JSON_NESTING = 16
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val MIME_PATTERN = Regex("(image|audio)/[A-Za-z0-9!#$&^_.+-]{1,100}")
        private val ASSET_PATH_PATTERN = Regex(
            "assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin"
        )
    }
}
