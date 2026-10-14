package com.qrhry.app.data.archive

import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.TaskScoringMode
import com.qrhry.app.domain.StationTaskType
import java.io.File

internal object GameArchiveLimits {
    const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 50L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
    const val MAX_ENTRIES = 128
    const val MAX_STATIONS = 64
    const val MAX_TASKS_PER_STATION = 32
    const val MAX_OPTIONS_PER_TASK = 32
    const val MAX_MEDIA_PER_STATION = 2
    const val CURRENT_FORMAT_VERSION = 2
}

data class GameArchiveManifest(
    val formatVersion: Int,
    val title: String,
    val scoringMode: TaskScoringMode,
    val stations: List<GameArchiveStation>
)

data class GameArchiveStation(
    val title: String,
    val bodyText: String,
    val position: Int,
    val media: List<GameArchiveMedia>,
    val tasks: List<GameArchiveTask>
)

data class GameArchiveMedia(
    val entryPath: String,
    val mediaType: StationMediaType,
    val mimeType: String,
    val originalFilename: String?,
    val byteSize: Long,
    val checksum: String
)

data class GameArchiveTask(
    val prompt: String,
    val position: Int,
    val points: Int,
    val type: StationTaskType = StationTaskType.MULTIPLE_CHOICE,
    val options: List<GameArchiveOption> = emptyList(),
    val correctBoolean: Boolean? = null,
    val correctText: String? = null
)

data class GameArchiveOption(
    val text: String,
    val position: Int,
    val isCorrect: Boolean
)

data class GameArchiveBundle(
    val manifest: GameArchiveManifest,
    val mediaFiles: Map<String, File>
)
