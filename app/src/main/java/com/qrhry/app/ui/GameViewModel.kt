package com.qrhry.app.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qrhry.app.data.GameRepository
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.data.local.GameAssetStore
import com.qrhry.app.data.local.ImportedAsset
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.GameSession
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.SessionProgress
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationQrLookup
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.StationMedia
import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.TaskSubmissionResult
import com.qrhry.app.qr.QrPayloadParseResult
import com.qrhry.app.qr.StationQrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameScreenState(
    val games: List<Game> = emptyList(),
    val activeSessions: Map<Long, GameSession> = emptyMap(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isLoadingSession: Boolean = false,
    val error: String? = null,
    val savedGameId: Long? = null,
    val route: GameScreenRoute = GameScreenRoute.HOME,
    val qrScanStatus: QrScanStatus = QrScanStatus.READY,
    val isLookingUpStation: Boolean = false,
    val selectedStation: StationQrLookup? = null,
    val sessionProgress: SessionProgress? = null,
    val stationScanResult: StationScanResult? = null,
    val scannerSessionId: Long? = null,
    val scannerReturnRoute: GameScreenRoute = GameScreenRoute.CREATE,
    val editingStation: com.qrhry.app.domain.Station? = null,
    val isSavingStation: Boolean = false,
    val isSavingTaskAnswer: Boolean = false,
    val taskSubmissionCorrect: Boolean? = null
)

enum class GameScreenRoute {
    HOME,
    CREATE,
    EDIT_STATION,
    PLAY_LIST,
    PLAY_SESSION,
    SCANNER,
    STATION
}

enum class QrScanStatus {
    READY,
    MALFORMED,
    UNKNOWN,
    SCANNER_ERROR
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GameRepository(GameDatabaseHelper(application))
    private val assetStore = GameAssetStore(application.filesDir)
    private val mutableState = MutableStateFlow(GameScreenState())
    val state = mutableState.asStateFlow()

    init {
        refreshGames()
    }

    fun createGame(
        title: String,
        stations: List<StationDraft>,
        mediaByStation: List<Pair<Uri?, Uri?>> = emptyList()
    ) {
        val draft = GameDraft(title, stations)
        GameDraftValidator.validationError(draft)?.let { message ->
            mutableState.value = mutableState.value.copy(error = message)
            return
        }
        if (mutableState.value.isSaving) return

        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isSaving = true, error = null)
            try {
                val imported = mutableListOf<Pair<Long, ImportedAsset>>()
                val (createdGameId, games) = withContext(Dispatchers.IO) {
                    val created = repository.createGame(draft)
                    try {
                        created.stations.forEachIndexed { index, station ->
                            val mediaUris = mediaByStation.getOrNull(index) ?: (null to null)
                            mediaUris.first?.let { uri ->
                                imported += station.id to stageAndPromote(
                                    uri,
                                    created.gameUuid,
                                    StationMediaType.IMAGE
                                )
                            }
                            mediaUris.second?.let { uri ->
                                imported += station.id to stageAndPromote(
                                    uri,
                                    created.gameUuid,
                                    StationMediaType.AUDIO
                                )
                            }
                        }
                        val oldAssets = repository.replaceGameStationMedia(
                            imported.map { (stationId, asset) -> stationId to asset.toMedia(stationId) }
                        )
                        oldAssets.forEach { assetStore.delete(created.gameUuid, it.relativePath) }
                        created.id to repository.getAllGames().map(::hydrateGame)
                    } catch (exception: Exception) {
                        imported.forEach { (_, asset) -> assetStore.discard(asset) }
                        throw exception
                    }
                }
                mutableState.value = mutableState.value.copy(
                    games = games,
                    isSaving = false,
                    savedGameId = createdGameId
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = exception.message ?: "The game could not be saved."
                )
            }
        }
    }

    fun editStation(station: com.qrhry.app.domain.Station) {
        mutableState.value = mutableState.value.copy(
            editingStation = hydrateStation(station),
            route = GameScreenRoute.EDIT_STATION,
            error = null
        )
    }

    fun cancelStationEdit() {
        mutableState.value = mutableState.value.copy(
            editingStation = null,
            route = GameScreenRoute.CREATE,
            error = null
        )
    }

    fun saveStationContent(
        stationId: Long,
        title: String,
        bodyText: String,
        imageUri: Uri?,
        audioUri: Uri?,
        tasks: List<MultipleChoiceTaskDraft>
    ) {
        if (mutableState.value.isSavingStation) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isSavingStation = true, error = null)
            val promoted = mutableListOf<ImportedAsset>()
            try {
                val result = withContext(Dispatchers.IO) {
                    val gameUuid = repository.getGameUuidForStation(stationId)
                        ?: throw IllegalArgumentException("The station no longer exists.")
                    val image = imageUri?.let {
                        stageAndPromote(it, gameUuid, StationMediaType.IMAGE).also(promoted::add)
                    }
                    val audio = audioUri?.let {
                        stageAndPromote(it, gameUuid, StationMediaType.AUDIO).also(promoted::add)
                    }
                    val replaced = repository.updateStationContent(
                        stationId,
                        title,
                        bodyText,
                        listOfNotNull(image?.toMedia(stationId), audio?.toMedia(stationId)),
                        tasks
                    ) ?: run {
                        throw IllegalStateException("The station could not be updated.")
                    }
                    replaced.forEach { assetStore.delete(gameUuid, it.relativePath) }
                    repository.getAllGames().map(::hydrateGame)
                }
                mutableState.value = mutableState.value.copy(
                    games = result,
                    editingStation = null,
                    isSavingStation = false,
                    route = GameScreenRoute.CREATE
                )
            } catch (exception: Exception) {
                withContext(Dispatchers.IO) { promoted.forEach(assetStore::discard) }
                mutableState.value = mutableState.value.copy(
                    isSavingStation = false,
                    error = exception.message ?: "Station content could not be saved."
                )
            }
        }
    }

    fun removeStationMedia(stationId: Long, mediaType: StationMediaType) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val gameUuid = repository.getGameUuidForStation(stationId) ?: return@withContext
                    repository.removeStationMedia(stationId, mediaType)?.let {
                        assetStore.delete(gameUuid, it.relativePath)
                    }
                    repository.getAllGames().map(::hydrateGame)
                }
                val station = mutableState.value.editingStation
                if (station?.id == stationId) {
                    val refreshed = withContext(Dispatchers.IO) { repository.getGame(station.gameId) }
                        ?.stations?.firstOrNull { it.id == stationId }
                    mutableState.value = mutableState.value.copy(editingStation = refreshed)
                }
                refreshGames()
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.message)
            }
        }
    }

    fun selectTaskOption(taskId: String, optionId: String) {
        val sessionId = mutableState.value.sessionProgress?.session?.id ?: return
        viewModelScope.launch {
            try {
                val progress = withContext(Dispatchers.IO) {
                    repository.savePendingTaskSelection(sessionId, taskId, optionId)
                }
                mutableState.value = mutableState.value.copy(
                    sessionProgress = hydrateProgress(progress),
                    taskSubmissionCorrect = null,
                    error = null
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    error = exception.message ?: "The answer could not be selected."
                )
            }
        }
    }

    fun submitTaskAnswer(taskId: String) {
        val sessionId = mutableState.value.sessionProgress?.session?.id ?: return
        if (mutableState.value.isSavingTaskAnswer) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isSavingTaskAnswer = true, error = null)
            try {
                when (val result = withContext(Dispatchers.IO) {
                    repository.submitTaskAttempt(sessionId, taskId)
                }) {
                    is TaskSubmissionResult.Submitted -> mutableState.value = mutableState.value.copy(
                        sessionProgress = hydrateProgress(result.progress),
                        taskSubmissionCorrect = result.isCorrect,
                        isSavingTaskAnswer = false
                    )
                    TaskSubmissionResult.NoPendingSelection -> throw IllegalStateException("Select an answer first.")
                    TaskSubmissionResult.TaskAlreadyCompleted -> throw IllegalStateException("This task is already complete.")
                    TaskSubmissionResult.TaskUnavailable -> throw IllegalStateException("This task is not available at the current station.")
                    TaskSubmissionResult.SessionUnavailable -> throw IllegalStateException("The game session is no longer active.")
                }
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isSavingTaskAnswer = false,
                    error = exception.message ?: "The answer could not be submitted."
                )
            }
        }
    }

    private fun stageAndPromote(
        uri: Uri,
        gameUuid: String,
        mediaType: StationMediaType
    ): ImportedAsset {
        val imported = assetStore.stageImport(
            getApplication<Application>().contentResolver,
            uri,
            gameUuid,
            mediaType,
            queryDisplayName(getApplication<Application>().contentResolver, uri)
        )
        assetStore.promote(imported)
        return imported
    }

    private fun ImportedAsset.toMedia(stationId: Long) = StationMedia(
        id = mediaId,
        stationId = stationId,
        mediaType = if (mimeType.startsWith("image/")) StationMediaType.IMAGE else StationMediaType.AUDIO,
        relativePath = relativePath,
        mimeType = mimeType,
        originalFilename = originalFilename,
        checksum = checksum,
        byteSize = byteSize,
        displayOrder = 0
    )

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun hydrateGame(game: Game): Game = game.copy(
        stations = game.stations.map(::hydrateStation)
    )

    private fun hydrateProgress(progress: SessionProgress): SessionProgress {
        val stations = progress.stations.map(::hydrateStation)
        val nextStation = stations.firstOrNull { it.id == progress.nextStation?.id }
        val currentStation = stations.firstOrNull { it.id == progress.currentStation?.id }
        val taskProgress = progress.taskProgress.map { taskState ->
            val task = currentStation?.tasks?.firstOrNull { it.id == taskState.task.id } ?: taskState.task
            taskState.copy(task = task)
        }
        return progress.copy(
            stations = stations,
            nextStation = nextStation,
            currentStation = currentStation,
            taskProgress = taskProgress
        )
    }

    private fun hydrateScanResult(result: StationScanResult): StationScanResult = when (result) {
        is StationScanResult.Accepted -> result.copy(
            station = hydrateStation(result.station),
            progress = hydrateProgress(result.progress)
        )
        is StationScanResult.AlreadyVisited -> result.copy(
            station = hydrateStation(result.station),
            progress = hydrateProgress(result.progress)
        )
        is StationScanResult.WrongGame -> result.copy(station = hydrateStation(result.station))
        is StationScanResult.OutOfOrder -> result.copy(
            expectedStation = hydrateStation(result.expectedStation),
            scannedStation = hydrateStation(result.scannedStation)
        )
        StationScanResult.UnknownQr,
        StationScanResult.SessionCompleted,
        StationScanResult.SessionUnavailable -> result
    }

    private fun hydrateStation(station: com.qrhry.app.domain.Station) = station.copy(
        media = station.media.map { media ->
            val gameUuid = repository.getGameUuidForStation(station.id)
            val file = gameUuid?.let { runCatching { assetStore.resolve(it, media.relativePath) }.getOrNull() }
            media.copy(localPath = file?.absolutePath, isAvailable = file?.isFile == true)
        }
    )


    fun refreshGames() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                val (games, activeSessions) = withContext(Dispatchers.IO) {
                    repository.getAllGames().map(::hydrateGame) to
                        repository.getActiveSessions().associateBy { it.gameId }
                }
                mutableState.value = mutableState.value.copy(
                    games = games,
                    activeSessions = activeSessions,
                    isLoading = false
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = exception.message ?: "Saved games could not be loaded."
                )
            }
        }
    }

    fun openScanner() {
        mutableState.value = mutableState.value.copy(
            route = GameScreenRoute.SCANNER,
            qrScanStatus = QrScanStatus.READY,
            selectedStation = null,
            scannerSessionId = null,
            scannerReturnRoute = GameScreenRoute.CREATE,
            stationScanResult = null
        )
    }

    fun openCreate() {
        mutableState.value = mutableState.value.copy(route = GameScreenRoute.CREATE)
    }

    fun openPlayList() {
        mutableState.value = mutableState.value.copy(route = GameScreenRoute.PLAY_LIST)
        refreshGames()
    }

    fun startOrResumeSession(gameId: Long) {
        if (mutableState.value.isLoadingSession) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoadingSession = true, error = null)
            try {
                val progress = withContext(Dispatchers.IO) {
                    hydrateProgress(repository.startOrResumeSession(gameId))
                }
                val sessions = withContext(Dispatchers.IO) {
                    repository.getActiveSessions().associateBy { it.gameId }
                }
                mutableState.value = mutableState.value.copy(
                    route = GameScreenRoute.PLAY_SESSION,
                    sessionProgress = progress,
                    stationScanResult = null,
                    activeSessions = sessions,
                    isLoadingSession = false
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoadingSession = false,
                    error = exception.message ?: "The game session could not be started."
                )
            }
        }
    }

    fun openSessionScanner() {
        val session = mutableState.value.sessionProgress?.session ?: return
        if (session.status != GameSessionStatus.IN_PROGRESS) return
        mutableState.value = mutableState.value.copy(
            route = GameScreenRoute.SCANNER,
            scannerSessionId = session.id,
            scannerReturnRoute = GameScreenRoute.PLAY_SESSION,
            qrScanStatus = QrScanStatus.READY,
            stationScanResult = null,
            error = null
        )
    }

    fun processQrPayload(payload: String?) {
        when (val parsed = StationQrPayload.parse(payload)) {
            QrPayloadParseResult.Malformed -> {
                mutableState.value = mutableState.value.copy(
                    route = GameScreenRoute.SCANNER,
                    qrScanStatus = QrScanStatus.MALFORMED,
                    isLookingUpStation = false
                )
            }
            is QrPayloadParseResult.Valid -> viewModelScope.launch {
                mutableState.value = mutableState.value.copy(isLookingUpStation = true)
                try {
                    val currentState = mutableState.value
                    val sessionId = currentState.scannerSessionId
                    if (sessionId != null) {
                        val result = withContext(Dispatchers.IO) {
                            hydrateScanResult(repository.recordStationScan(sessionId, parsed.stationToken))
                        }
                        val progress = when (result) {
                            is StationScanResult.Accepted -> result.progress
                            is StationScanResult.AlreadyVisited -> result.progress
                            else -> currentState.sessionProgress
                        }
                        mutableState.value = mutableState.value.copy(
                            route = if (result is StationScanResult.Accepted) {
                                GameScreenRoute.PLAY_SESSION
                            } else {
                                GameScreenRoute.SCANNER
                            },
                            sessionProgress = progress,
                            stationScanResult = result,
                            taskSubmissionCorrect = null,
                            isLookingUpStation = false
                        )
                        refreshGames()
                        return@launch
                    }

                    val station = withContext(Dispatchers.IO) {
                        repository.findStationByQrToken(parsed.stationToken)?.let { lookup ->
                            val media = repository.getGame(lookup.station.gameId)
                                ?.stations?.firstOrNull { it.id == lookup.station.id }?.media.orEmpty()
                            lookup.copy(station = hydrateStation(lookup.station.copy(media = media)))
                        }
                    }
                    mutableState.value = if (station == null) {
                        mutableState.value.copy(
                            qrScanStatus = QrScanStatus.UNKNOWN,
                            isLookingUpStation = false
                        )
                    } else {
                        mutableState.value.copy(
                            route = GameScreenRoute.STATION,
                            selectedStation = station,
                            isLookingUpStation = false
                        )
                    }
                } catch (exception: Exception) {
                    mutableState.value = mutableState.value.copy(
                        qrScanStatus = QrScanStatus.SCANNER_ERROR,
                        isLookingUpStation = false,
                        error = exception.message
                    )
                }
            }
        }
    }

    fun onQrScannerError() {
        mutableState.value = mutableState.value.copy(
            qrScanStatus = QrScanStatus.SCANNER_ERROR,
            isLookingUpStation = false
        )
    }

    fun onQrScanCancelled() {
        mutableState.value = mutableState.value.copy(
            qrScanStatus = QrScanStatus.READY,
            isLookingUpStation = false
        )
    }

    fun navigateBack() {
        mutableState.value = when (mutableState.value.route) {
            GameScreenRoute.HOME -> mutableState.value
            GameScreenRoute.CREATE, GameScreenRoute.EDIT_STATION, GameScreenRoute.PLAY_LIST -> mutableState.value.copy(
                route = GameScreenRoute.HOME
            )
            GameScreenRoute.PLAY_SESSION -> mutableState.value.copy(
                route = GameScreenRoute.PLAY_LIST,
                stationScanResult = null
            )
            GameScreenRoute.SCANNER -> mutableState.value.copy(
                route = mutableState.value.scannerReturnRoute,
                scannerSessionId = null
            )
            GameScreenRoute.STATION -> mutableState.value.copy(route = GameScreenRoute.SCANNER)
        }
    }
}