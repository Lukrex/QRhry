package com.qrhry.app.domain

data class Game(
    val id: Long,
    val title: String,
    val stations: List<Station>,
    val gameUuid: String = "",
    val contentVersion: Int = 1,
    val updatedAt: Long = 0L,
    val scoringMode: TaskScoringMode = TaskScoringMode.EVENTUAL_CORRECT
)

enum class TaskScoringMode {
    EVENTUAL_CORRECT,
    FIRST_TRY_ONLY
}

data class Station(
    val id: Long,
    val gameId: Long,
    val title: String,
    val bodyText: String,
    val position: Int,
    val qrToken: String,
    val media: List<StationMedia> = emptyList(),
    val tasks: List<StationTask> = emptyList()
)

enum class StationTaskType {
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    SHORT_TEXT
}

sealed interface StationTask {
    val id: String
    val stationId: Long
    val prompt: String
    val position: Int
    val points: Int
    val type: StationTaskType
}

data class MultipleChoiceTask(
    override val id: String,
    override val stationId: Long,
    override val prompt: String,
    override val position: Int,
    val options: List<TaskOption>,
    override val points: Int = 100
) : StationTask {
    override val type: StationTaskType = StationTaskType.MULTIPLE_CHOICE
}

data class TrueFalseTask(
    override val id: String,
    override val stationId: Long,
    override val prompt: String,
    override val position: Int,
    val correctAnswer: Boolean,
    override val points: Int = 100
) : StationTask {
    override val type: StationTaskType = StationTaskType.TRUE_FALSE
}

data class ShortTextTask(
    override val id: String,
    override val stationId: Long,
    override val prompt: String,
    override val position: Int,
    val correctAnswer: String,
    override val points: Int = 100
) : StationTask {
    override val type: StationTaskType = StationTaskType.SHORT_TEXT
}

data class TaskOption(
    val id: String,
    val taskId: String,
    val text: String,
    val position: Int,
    val isCorrect: Boolean
)

sealed interface StationTaskDraft {
    val id: String
    val prompt: String
    val position: Int
    val points: Int
    val type: StationTaskType
}

data class MultipleChoiceTaskDraft(
    override val id: String,
    override val prompt: String,
    override val position: Int,
    val options: List<TaskOptionDraft>,
    override val points: Int = 100
) : StationTaskDraft {
    override val type: StationTaskType = StationTaskType.MULTIPLE_CHOICE
}

data class TrueFalseTaskDraft(
    override val id: String,
    override val prompt: String,
    override val position: Int,
    val correctAnswer: Boolean,
    override val points: Int = 100
) : StationTaskDraft {
    override val type: StationTaskType = StationTaskType.TRUE_FALSE
}

data class ShortTextTaskDraft(
    override val id: String,
    override val prompt: String,
    override val position: Int,
    val correctAnswer: String,
    override val points: Int = 100
) : StationTaskDraft {
    override val type: StationTaskType = StationTaskType.SHORT_TEXT
}

data class TaskOptionDraft(
    val id: String,
    val text: String,
    val position: Int,
    val isCorrect: Boolean
)

sealed interface TaskAnswer {
    data class Option(val optionId: String) : TaskAnswer
    data class BooleanValue(val value: Boolean) : TaskAnswer
    data class TextValue(val value: String) : TaskAnswer
}

data class StationVisit(
    val sessionId: Long,
    val stationId: Long,
    val visitedAt: Long,
    val completedAt: Long?
) {
    val isCompleted: Boolean
        get() = completedAt != null
}

data class TaskAttempt(
    val id: String,
    val sessionId: Long,
    val taskId: String,
    val selectedOptionId: String?,
    val selectedOptionTextSnapshot: String,
    val promptTextSnapshot: String,
    val correctness: Boolean?,
    val selectedAt: Long,
    val submittedAt: Long?,
    val awardedPoints: Int = 0,
    val isFinalized: Boolean = false
) {
    val isPending: Boolean
        get() = submittedAt == null
}

data class TaskProgress(
    val task: StationTask,
    val attempts: List<TaskAttempt>,
    val pendingAttempt: TaskAttempt?,
    val isCompleted: Boolean
) {
    val lastSubmittedAttempt: TaskAttempt?
        get() = attempts.lastOrNull { it.submittedAt != null }

    val awardedPoints: Int
        get() = attempts.firstOrNull { it.correctness == true && it.submittedAt != null }?.awardedPoints ?: 0

    val isSkipped: Boolean
        get() = isCompleted && attempts.none { it.correctness == true && it.submittedAt != null }

    val hasIncorrectSubmission: Boolean
        get() = attempts.any { it.correctness == false && it.submittedAt != null }
}

enum class StationMediaType {
    IMAGE,
    AUDIO
}

data class StationMedia(
    val id: String,
    val stationId: Long,
    val mediaType: StationMediaType,
    val relativePath: String,
    val mimeType: String,
    val originalFilename: String?,
    val checksum: String,
    val byteSize: Long,
    val displayOrder: Int,
    val localPath: String? = null,
    val isAvailable: Boolean = true
)

data class GameDraft(
    val title: String,
    val stations: List<StationDraft>,
    val scoringMode: TaskScoringMode = TaskScoringMode.EVENTUAL_CORRECT
)

data class StationDraft(
    val title: String,
    val bodyText: String,
    val tasks: List<StationTaskDraft> = emptyList()
)

data class StationQrLookup(
    val gameTitle: String,
    val station: Station
)

enum class GameSessionStatus {
    IN_PROGRESS,
    COMPLETED
}

data class GameSession(
    val id: Long,
    val gameId: Long,
    val status: GameSessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val scoringMode: TaskScoringMode = TaskScoringMode.EVENTUAL_CORRECT
)

data class CompletedSessionResult(
    val sessionId: Long,
    val gameId: Long,
    val gameTitle: String,
    val completedAt: Long,
    val score: Long,
    val completedTaskCount: Int,
    val totalTaskCount: Int?,
    val completedStationCount: Int,
    val totalStationCount: Int?
)

data class SessionProgress(
    val session: GameSession,
    val gameTitle: String,
    val stations: List<Station>,
    val visitedStationIds: Set<Long>,
    val nextStation: Station?,
    val completedStationIds: Set<Long> = emptySet(),
    val stationVisits: List<StationVisit> = emptyList(),
    val currentStation: Station? = null,
    val taskProgress: List<TaskProgress> = emptyList(),
    val score: Long = 0L,
    val completedTaskCount: Int = 0,
    val resultTotalTaskCount: Int? = null,
    val resultTotalStationCount: Int? = null
) {
    val completedStationCount: Int
        get() = completedStationIds.size

    val totalStationCount: Int
        get() = stations.size
}

sealed interface StationScanResult {
    data class Accepted(
        val station: Station,
        val progress: SessionProgress,
        val resumed: Boolean = false
    ) : StationScanResult
    data object UnknownQr : StationScanResult
    data class WrongGame(val station: Station) : StationScanResult
    data class OutOfOrder(val expectedStation: Station, val scannedStation: Station) : StationScanResult
    data class AlreadyVisited(val station: Station, val progress: SessionProgress) : StationScanResult
    data object SessionCompleted : StationScanResult
    data object SessionUnavailable : StationScanResult
}

sealed interface TaskSubmissionResult {
    data class Submitted(
        val isCorrect: Boolean,
        val progress: SessionProgress,
        val pointsAwarded: Int = 0
    ) : TaskSubmissionResult
    data object NoPendingSelection : TaskSubmissionResult
    data object TaskAlreadyCompleted : TaskSubmissionResult
    data object TaskUnavailable : TaskSubmissionResult
    data object SessionUnavailable : TaskSubmissionResult
}
