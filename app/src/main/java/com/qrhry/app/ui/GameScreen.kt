package com.qrhry.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.GameSessionStatus
import com.qrhry.app.domain.SessionProgress
import com.qrhry.app.domain.StationScanResult
import com.qrhry.app.domain.Station
import com.qrhry.app.domain.StationQrLookup
import com.qrhry.app.domain.StationDraft
import com.qrhry.app.qr.StationQrCodeGenerator

private data class StationFormState(
    val title: String = "",
    val bodyText: String = ""
)

@Composable
fun GameScreen(viewModel: GameViewModel, onLaunchQrScanner: () -> Unit) {
    val state by viewModel.state.collectAsState()

    BackHandler(enabled = state.route != GameScreenRoute.HOME) {
        viewModel.navigateBack()
    }

    when (state.route) {
        GameScreenRoute.HOME -> HomeScreen(
            onCreate = viewModel::openCreate,
            onPlay = viewModel::openPlayList
        )
        GameScreenRoute.CREATE -> GameLibraryScreen(state, viewModel)
        GameScreenRoute.PLAY_LIST -> PlayListScreen(state, viewModel)
        GameScreenRoute.PLAY_SESSION -> state.sessionProgress?.let { progress ->
            PlaySessionScreen(
                progress = progress,
                scanResult = state.stationScanResult,
                isLoading = state.isLoadingSession,
                onScan = viewModel::openSessionScanner,
                onBack = viewModel::navigateBack
            )
        }
        GameScreenRoute.SCANNER -> QrScannerScreen(
            status = state.qrScanStatus,
            isLookingUpStation = state.isLookingUpStation,
            sessionScanResult = if (state.scannerSessionId != null) state.stationScanResult else null,
            onScan = onLaunchQrScanner,
            onBack = viewModel::navigateBack
        )
        GameScreenRoute.STATION -> state.selectedStation?.let { station ->
            StationDetailScreen(station, onBack = viewModel::navigateBack)
        }
    }
}

@Composable
private fun HomeScreen(onCreate: () -> Unit, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("QRhry", style = MaterialTheme.typography.headlineMedium)
        Text("Choose a mode", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text("Create / Edit")
        }
        OutlinedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
            Text("Play")
        }
    }
}

@Composable
private fun GameLibraryScreen(
    state: GameScreenState,
    viewModel: GameViewModel
) {
    var gameTitle by remember { mutableStateOf("") }
    var stationForms by remember {
        mutableStateOf(listOf(StationFormState(), StationFormState()))
    }

    LaunchedEffect(state.savedGameId) {
        if (state.savedGameId != null) {
            gameTitle = ""
            stationForms = listOf(StationFormState(), StationFormState())
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Create / Edit", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = viewModel::navigateBack) { Text("Home") }
                }
            OutlinedButton(
                onClick = viewModel::openScanner,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan station QR")
            }
            OutlinedTextField(
                value = gameTitle,
                onValueChange = { gameTitle = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Game title") },
                singleLine = true
            )

            stationForms.forEachIndexed { index, form ->
                Text("Station ${index + 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { value -> stationForms = stationForms.updated(index) { it.copy(title = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Station title") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.bodyText,
                    onValueChange = { value -> stationForms = stationForms.updated(index) { it.copy(bodyText = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Station text") },
                    minLines = 2
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { stationForms = stationForms + StationFormState() }
                ) {
                    Text("Add station")
                }
                if (stationForms.size > 2) {
                    OutlinedButton(
                        onClick = { stationForms = stationForms.dropLast(1) }
                    ) {
                        Text("Remove last")
                    }
                }
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    viewModel.createGame(
                        gameTitle,
                        stationForms.map { StationDraft(it.title, it.bodyText) }
                    )
                },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isSaving) "Saving..." else "Save game")
            }

            Spacer(Modifier.height(8.dp))
            Text("Saved games", style = MaterialTheme.typography.titleLarge)
            if (state.isLoading && state.games.isEmpty()) {
                Text("Loading saved games...")
            } else if (state.games.isEmpty()) {
                Text("No games yet.")
            } else {
                state.games.forEach { game -> SavedGame(game) }
            }
        }
    }
}

@Composable
private fun PlayListScreen(state: GameScreenState, viewModel: GameViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Play", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = viewModel::navigateBack) { Text("Home") }
        }
        when {
            state.isLoading && state.games.isEmpty() -> Text("Loading games...")
            state.games.isEmpty() -> Text("Create a game with stations before playing.")
            else -> state.games.forEach { game ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(game.title, style = MaterialTheme.typography.titleMedium)
                        Text("${game.stations.size} stations")
                        val activeSession = state.activeSessions[game.id]
                        Button(
                            onClick = { viewModel.startOrResumeSession(game.id) },
                            enabled = !state.isLoadingSession,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (activeSession == null) "Start" else "Continue")
                        }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PlaySessionScreen(
    progress: SessionProgress,
    scanResult: StationScanResult?,
    isLoading: Boolean,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) { Text("Back to Play") }
        Text(progress.gameTitle, style = MaterialTheme.typography.titleLarge)
        Text(
            "${progress.completedStationCount} of ${progress.totalStationCount} stations completed",
            style = MaterialTheme.typography.titleMedium
        )
        if (progress.session.status == GameSessionStatus.COMPLETED) {
            Text("Game completed!", style = MaterialTheme.typography.headlineSmall)
        } else {
            progress.nextStation?.let { next ->
                Text("Next station", style = MaterialTheme.typography.titleMedium)
                Text(next.title, style = MaterialTheme.typography.headlineSmall)
                if (next.bodyText.isNotBlank()) Text(next.bodyText)
            }
            Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Scan next station")
            }
        }
        scanResult?.let { result ->
            Text(
                result.message(),
                color = if (result is StationScanResult.Accepted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun SavedGame(game: Game) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(game.title, style = MaterialTheme.typography.titleMedium)
            game.stations.forEachIndexed { index, station ->
                Text("${index + 1}. ${station.title}", style = MaterialTheme.typography.bodyMedium)
                if (station.bodyText.isNotBlank()) {
                    Text(station.bodyText, style = MaterialTheme.typography.bodySmall)
                }
                StationQrImage(
                    station = station,
                    modifier = Modifier.size(144.dp)
                )
            }
        }
    }
}

@Composable
private fun QrScannerScreen(
    status: QrScanStatus,
    isLookingUpStation: Boolean,
    sessionScanResult: StationScanResult?,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Scan station QR", style = MaterialTheme.typography.headlineSmall)
        val message = sessionScanResult?.message() ?: when {
                isLookingUpStation -> "Looking up station..."
                status == QrScanStatus.MALFORMED -> "Malformed QR: this is not a valid QRhry station code."
                status == QrScanStatus.UNKNOWN -> "Unknown QR: no saved station matches this code."
                status == QrScanStatus.SCANNER_ERROR -> "The scanner or station lookup failed. Try again."
                else -> "Scan a station code saved in this app."
            }
        Text(
            message,
            color = if (status == QrScanStatus.MALFORMED ||
                status == QrScanStatus.UNKNOWN || status == QrScanStatus.SCANNER_ERROR ||
                (sessionScanResult != null && sessionScanResult !is StationScanResult.Accepted)
            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Button(onClick = onScan, enabled = !isLookingUpStation) {
            Text("Open camera scanner")
        }
        OutlinedButton(onClick = onBack) {
            Text(if (sessionScanResult != null) "Back to session" else "Back")
        }
    }
}

private fun StationScanResult.message(): String = when (this) {
    is StationScanResult.Accepted -> if (progress.session.status == GameSessionStatus.COMPLETED) {
        "${station.title} completed. Game completed!"
    } else {
        "${station.title} completed. Continue to the next station."
    }
    StationScanResult.UnknownQr -> "Unknown QR: no saved station matches this code."
    is StationScanResult.WrongGame -> "${station.title} belongs to another game."
    is StationScanResult.OutOfOrder ->
        "Wrong station order. Scan ${expectedStation.title} next."
    is StationScanResult.AlreadyVisited -> "${station.title} was already completed."
    StationScanResult.SessionCompleted -> "This game session is already completed."
    StationScanResult.SessionUnavailable -> "This game session is no longer available."
}

@Composable
private fun StationDetailScreen(lookup: StationQrLookup, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(lookup.gameTitle, style = MaterialTheme.typography.titleMedium)
        Text(lookup.station.title, style = MaterialTheme.typography.headlineSmall)
        Text(lookup.station.bodyText.ifBlank { "This station has no text." })
        StationQrImage(lookup.station, Modifier.size(224.dp))
        OutlinedButton(onClick = onBack) {
            Text("Back to scanner")
        }
    }
}

@Composable
private fun StationQrImage(station: Station, modifier: Modifier = Modifier) {
    val bitmap = remember(station.qrToken) {
        StationQrCodeGenerator.createBitmap(station.qrToken, size = 384)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code for ${station.title}",
        modifier = modifier
    )
}

private fun <T> List<T>.updated(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) transform(item) else item }