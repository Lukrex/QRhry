package com.qrhry.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qrhry.app.data.GameRepository
import com.qrhry.app.data.local.GameDatabaseHelper
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameDraft
import com.qrhry.app.domain.GameDraftValidator
import com.qrhry.app.domain.StationQrLookup
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.qr.QrPayloadParseResult
import com.qrhry.app.qr.StationQrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameScreenState(
    val games: List<Game> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedGameId: Long? = null,
    val route: GameScreenRoute = GameScreenRoute.GAMES,
    val qrScanStatus: QrScanStatus = QrScanStatus.READY,
    val isLookingUpStation: Boolean = false,
    val selectedStation: StationQrLookup? = null
)

enum class GameScreenRoute {
    GAMES,
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
                val games = withContext(Dispatchers.IO) { repository.getAllGames() }
                mutableState.value = mutableState.value.copy(games = games, isLoading = false)
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
            selectedStation = null
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
            GameScreenRoute.GAMES -> mutableState.value
            GameScreenRoute.SCANNER -> mutableState.value.copy(route = GameScreenRoute.GAMES)
            GameScreenRoute.STATION -> mutableState.value.copy(route = GameScreenRoute.SCANNER)
        }
    }
}