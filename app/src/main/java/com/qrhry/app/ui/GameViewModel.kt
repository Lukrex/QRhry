package com.qrhry.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qrhry.app.data.GameRepository
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.GameSession
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.SessionProgress
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.domain.StationQrLookup
import com.qrhry.app.domain.StationScanResult
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
    val scannerReturnRoute: GameScreenRoute = GameScreenRoute.CREATE
)

enum class GameScreenRoute {
    HOME,
    CREATE,
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
    private val mutableState = MutableStateFlow(GameScreenState())
    val state = mutableState.asStateFlow()

    init {
        refreshGames()
    }

    fun createGame(title: String, stations: List<StationDraft>) {
        val draft = GameDraft(title, stations)
        GameDraftValidator.validationError(draft)?.let { message ->
            mutableState.value = mutableState.value.copy(error = message)
            return
        }
        if (mutableState.value.isSaving) return

        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isSaving = true, error = null)
            try {
                val game = withContext(Dispatchers.IO) { repository.createGame(draft) }
                val games = withContext(Dispatchers.IO) { repository.getAllGames() }
                mutableState.value = mutableState.value.copy(
                    games = games,
                    isSaving = false,
                    savedGameId = game.id
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = exception.message ?: "The game could not be saved."
                )
            }
        }
    }

    fun refreshGames() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                val (games, activeSessions) = withContext(Dispatchers.IO) {
                    repository.getAllGames() to repository.getActiveSessions().associateBy { it.gameId }
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
                    repository.startOrResumeSession(gameId)
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
                            repository.recordStationScan(sessionId, parsed.stationToken)
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
                            isLookingUpStation = false
                        )
                        refreshGames()
                        return@launch
                    }

                    val station = withContext(Dispatchers.IO) {
                        repository.findStationByQrToken(parsed.stationToken)
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
            GameScreenRoute.CREATE, GameScreenRoute.PLAY_LIST -> mutableState.value.copy(
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