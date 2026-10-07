package com.qrhry.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.qrhry.app.domain.StationMediaType
import com.qrhry.app.domain.MultipleChoiceTask
import com.qrhry.app.domain.MultipleChoiceTaskDraft
import com.qrhry.app.domain.TaskOptionDraft
import com.qrhry.app.domain.TaskProgress
import com.qrhry.app.qr.StationQrCodeGenerator
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class StationFormState(
    val title: String = "",
    val bodyText: String = "",
    val imageUri: Uri? = null,
    val audioUri: Uri? = null,
    val task: MultipleChoiceTaskFormState? = null
)

private data class TaskOptionFormState(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val isCorrect: Boolean = false
)

private data class MultipleChoiceTaskFormState(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String = "",
    val options: List<TaskOptionFormState> = listOf(TaskOptionFormState(), TaskOptionFormState())
)

private fun MultipleChoiceTaskFormState.toDraft(position: Int) = MultipleChoiceTaskDraft(
    id = id,
    prompt = prompt,
    position = position,
    options = options.mapIndexed { index, option ->
        TaskOptionDraft(option.id, option.text, index, option.isCorrect)
    }
)

private fun MultipleChoiceTask.toFormState() = MultipleChoiceTaskFormState(
    id = id,
    prompt = prompt,
    options = options.map { TaskOptionFormState(it.id, it.text, it.isCorrect) }
)

private data class PendingMediaPick(
    val mediaType: StationMediaType,
    val onPicked: (Uri) -> Unit
)

@Composable
fun GameScreen(viewModel: GameViewModel, onLaunchQrScanner: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var pendingPick by remember { mutableStateOf<PendingMediaPick?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingPick
        pendingPick = null
        if (uri != null) target?.onPicked?.invoke(uri)
    }
    val pickMedia: (StationMediaType, (Uri) -> Unit) -> Unit = { mediaType, callback ->
        pendingPick = PendingMediaPick(mediaType, callback)
        mediaPicker.launch(arrayOf(if (mediaType == StationMediaType.IMAGE) "image/*" else "audio/*"))
    }

    BackHandler(enabled = state.route != GameScreenRoute.HOME) {
        viewModel.navigateBack()
    }

    when (state.route) {
        GameScreenRoute.HOME -> HomeScreen(
            onCreate = viewModel::openCreate,
            onPlay = viewModel::openPlayList
        )
        GameScreenRoute.CREATE -> GameLibraryScreen(state, viewModel, pickMedia)
        GameScreenRoute.EDIT_STATION -> state.editingStation?.let { station ->
            StationEditScreen(
                station = station,
                isSaving = state.isSavingStation,
                error = state.error,
                onPickMedia = pickMedia,
                onRemoveMedia = viewModel::removeStationMedia,
                onSave = viewModel::saveStationContent,
                onCancel = viewModel::cancelStationEdit
            )
        }
        GameScreenRoute.PLAY_LIST -> PlayListScreen(state, viewModel)
        GameScreenRoute.PLAY_SESSION -> state.sessionProgress?.let { progress ->
            PlaySessionScreen(
                progress = progress,
                scanResult = state.stationScanResult,
                isLoading = state.isLoadingSession,
                isSubmittingAnswer = state.isSavingTaskAnswer,
                taskSubmissionCorrect = state.taskSubmissionCorrect,
                onSelectOption = viewModel::selectTaskOption,
                onSubmitTask = viewModel::submitTaskAnswer,
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
    viewModel: GameViewModel,
    onPickMedia: (StationMediaType, (Uri) -> Unit) -> Unit
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        onPickMedia(StationMediaType.IMAGE) { uri ->
                            stationForms = stationForms.updated(index) { it.copy(imageUri = uri) }
                        }
                    }) { Text(if (form.imageUri == null) "Choose image" else "Replace image") }
                    OutlinedButton(onClick = {
                        onPickMedia(StationMediaType.AUDIO) { uri ->
                            stationForms = stationForms.updated(index) { it.copy(audioUri = uri) }
                        }
                    }) { Text(if (form.audioUri == null) "Choose audio" else "Replace audio") }
                }
                form.imageUri?.let { Text("Image selected: ${it.lastPathSegment}") }
                form.audioUri?.let { Text("Audio selected: ${it.lastPathSegment}") }
                if (form.task == null) {
                    OutlinedButton(onClick = {
                        stationForms = stationForms.updated(index) {
                            it.copy(task = MultipleChoiceTaskFormState())
                        }
                    }) { Text("Add multiple-choice task") }
                } else {
                    MultipleChoiceTaskEditor(
                        task = form.task,
                        onChange = { task ->
                            stationForms = stationForms.updated(index) { it.copy(task = task) }
                        },
                        onRemove = {
                            stationForms = stationForms.updated(index) { it.copy(task = null) }
                        }
                    )
                }
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
                        stationForms.map { form ->
                            StationDraft(
                                form.title,
                                form.bodyText,
                                listOfNotNull(form.task?.toDraft(0))
                            )
                        },
                        stationForms.map { it.imageUri to it.audioUri }
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
                state.games.forEach { game ->
                    SavedGame(game, onEditStation = viewModel::editStation)
                }
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
    isSubmittingAnswer: Boolean,
    taskSubmissionCorrect: Boolean?,
    onSelectOption: (String, String) -> Unit,
    onSubmitTask: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    val acceptedScan = scanResult as? StationScanResult.Accepted
    val completedScanStation = acceptedScan?.station?.takeIf { it.id in progress.completedStationIds }
    val displayedStation = progress.currentStation ?: completedScanStation
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
        displayedStation?.let { station ->
            Text(
                if (station.id in progress.completedStationIds) "Completed station" else "Current station",
                style = MaterialTheme.typography.titleMedium
            )
            Text(station.title, style = MaterialTheme.typography.headlineSmall)
            if (station.bodyText.isNotBlank()) Text(station.bodyText)
            station.media.filter { it.mediaType == StationMediaType.IMAGE }.forEach { StationImage(it) }
            station.media.filter { it.mediaType == StationMediaType.AUDIO }.forEach { StationAudioPlayer(it) }
        }
        if (progress.session.status == GameSessionStatus.COMPLETED) {
            Text("Game completed!", style = MaterialTheme.typography.headlineSmall)
        } else if (progress.currentStation != null) {
            progress.taskProgress.forEach { taskProgress ->
                MultipleChoiceTaskPlayer(
                    progress = taskProgress,
                    isSubmitting = isSubmittingAnswer,
                    onSelectOption = { optionId -> onSelectOption(taskProgress.task.id, optionId) },
                    onSubmit = { onSubmitTask(taskProgress.task.id) }
                )
            }
        } else if (progress.stationVisits.isEmpty()) {
            Text("Scan the first station QR code to begin.")
            Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Scan first station QR")
            }
        } else {
            progress.nextStation?.let { next ->
                Text("Next station", style = MaterialTheme.typography.titleMedium)
                Text(next.title, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan station")
                }
            }
        }
        if (progress.currentStation == null) {
            taskSubmissionCorrect?.let { correct ->
                Text(if (correct) "Correct answer." else "Incorrect answer. You can try again.")
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
private fun SavedGame(game: Game, onEditStation: (com.qrhry.app.domain.Station) -> Unit) {
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
                OutlinedButton(onClick = { onEditStation(station) }) {
                    Text("Edit station content")
                }
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
    is StationScanResult.Accepted -> when {
        progress.currentStation?.id == station.id ->
            if (resumed) "${station.title} visit restored. Complete its task(s) to continue."
            else "${station.title} visited. Complete its task(s) to continue."
        progress.session.status == GameSessionStatus.COMPLETED -> "${station.title} completed. Game completed!"
        else -> "${station.title} completed. Scan the next station."
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
        lookup.station.media.filter { it.mediaType == StationMediaType.IMAGE }.forEach { StationImage(it) }
        lookup.station.media.filter { it.mediaType == StationMediaType.AUDIO }.forEach { StationAudioPlayer(it) }
        StationQrImage(lookup.station, Modifier.size(224.dp))
        OutlinedButton(onClick = onBack) {
            Text("Back to scanner")
        }
    }
}

@Composable
private fun StationEditScreen(
    station: com.qrhry.app.domain.Station,
    isSaving: Boolean,
    error: String?,
    onPickMedia: (StationMediaType, (Uri) -> Unit) -> Unit,
    onRemoveMedia: (Long, StationMediaType) -> Unit,
    onSave: (Long, String, String, Uri?, Uri?, List<MultipleChoiceTaskDraft>) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(station.id) { mutableStateOf(station.title) }
    var bodyText by remember(station.id) { mutableStateOf(station.bodyText) }
    var imageUri by remember(station.id) { mutableStateOf<Uri?>(null) }
    var audioUri by remember(station.id) { mutableStateOf<Uri?>(null) }
    var tasks by remember(station.id) { mutableStateOf(station.tasks.map { it.toFormState() }) }
    val image = station.media.firstOrNull { it.mediaType == StationMediaType.IMAGE }
    val audio = station.media.firstOrNull { it.mediaType == StationMediaType.AUDIO }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Edit station", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(bodyText, { bodyText = it }, Modifier.fillMaxWidth(), label = { Text("Body text") }, minLines = 3)
        Text(if (imageUri != null) "New image selected" else image?.originalFilename ?: "No image")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.IMAGE) { imageUri = it } }) {
                Text(if (image == null) "Choose image" else "Replace image")
            }
            if (image != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.IMAGE) }) {
                Text("Remove image")
            }
        }
        Text(if (audioUri != null) "New audio selected" else audio?.originalFilename ?: "No audio")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.AUDIO) { audioUri = it } }) {
                Text(if (audio == null) "Choose audio" else "Replace audio")
            }
            if (audio != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.AUDIO) }) {
                Text("Remove audio")
            }
        }
        tasks.forEachIndexed { index, task ->
            MultipleChoiceTaskEditor(
                task = task,
                onChange = { updated -> tasks = tasks.updated(index) { updated } },
                onRemove = { tasks = tasks.filterIndexed { taskIndex, _ -> taskIndex != index } }
            )
        }
        if (tasks.isEmpty()) {
            OutlinedButton(onClick = { tasks = listOf(MultipleChoiceTaskFormState()) }) {
                Text("Add multiple-choice task")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                onSave(station.id, title, bodyText, imageUri, audioUri, tasks.mapIndexed { index, task ->
                    task.toDraft(index)
                })
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isSaving) "Saving..." else "Save station") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun StationImage(media: com.qrhry.app.domain.StationMedia) {
    if (!media.isAvailable || media.localPath == null) {
        Text("Image unavailable: ${media.originalFilename ?: "stored image"}")
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, media.localPath) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(media.localPath) }
    }
    if (bitmap == null) {
        Text("Image could not be loaded: ${media.originalFilename ?: "stored image"}")
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = media.originalFilename ?: "Station image",
            modifier = Modifier.fillMaxWidth().height(240.dp),
            contentScale = ContentScale.Fit
        )
    }
}

private fun decodeSampledBitmap(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1600 || bounds.outHeight / sampleSize > 1600) {
        sampleSize *= 2
    }
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}.getOrNull()

@Composable
private fun StationAudioPlayer(media: com.qrhry.app.domain.StationMedia) {
    val context = LocalContext.current
    val path = media.localPath
    if (!media.isAvailable || path == null || !File(path).isFile) {
        Text("Audio unavailable: ${media.originalFilename ?: "stored audio"}")
        return
    }
    var playbackError by remember(path) { mutableStateOf(false) }
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = true
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    if (playbackError) {
        Text("Audio could not be played: ${media.originalFilename ?: "stored audio"}")
    } else {
        AndroidView(
            factory = { viewContext -> PlayerView(viewContext).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(64.dp)
        )
    }
}

private fun <T> List<T>.updated(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { itemIndex, item -> if (itemIndex == index) transform(item) else item }

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

@Composable
private fun MultipleChoiceTaskEditor(
    task: MultipleChoiceTaskFormState,
    onChange: (MultipleChoiceTaskFormState) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Multiple-choice task", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRemove) { Text("Remove task") }
        }
        OutlinedTextField(
            value = task.prompt,
            onValueChange = { onChange(task.copy(prompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") }
        )
        task.options.forEachIndexed { index, option ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(
                    selected = option.isCorrect,
                    onClick = {
                        onChange(task.copy(options = task.options.mapIndexed { optionIndex, item ->
                            item.copy(isCorrect = optionIndex == index)
                        }))
                    }
                )
                OutlinedTextField(
                    value = option.text,
                    onValueChange = { value ->
                        onChange(task.copy(options = task.options.updated(index) { it.copy(text = value) }))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Option ${index + 1}") },
                    singleLine = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val options = task.options.toMutableList()
                        val moved = options.removeAt(index)
                        options.add(index - 1, moved)
                        onChange(task.copy(options = options))
                    },
                    enabled = index > 0
                ) { Text("Move up") }
                OutlinedButton(
                    onClick = {
                        val options = task.options.toMutableList()
                        val moved = options.removeAt(index)
                        options.add(index + 1, moved)
                        onChange(task.copy(options = options))
                    },
                    enabled = index < task.options.lastIndex
                ) { Text("Move down") }
                OutlinedButton(
                    onClick = { onChange(task.copy(options = task.options.filterIndexed { i, _ -> i != index })) }
                ) { Text("Remove option") }
            }
        }
        OutlinedButton(
            onClick = { onChange(task.copy(options = task.options + TaskOptionFormState())) }
        ) { Text("Add option") }
    }
}

@Composable
private fun MultipleChoiceTaskPlayer(
    progress: TaskProgress,
    isSubmitting: Boolean,
    onSelectOption: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(progress.task.prompt, style = MaterialTheme.typography.titleLarge)
        if (progress.isCompleted) {
            Text("Task complete.")
        } else {
            if (progress.pendingAttempt == null) {
                progress.lastSubmittedAttempt?.takeIf { it.correctness == false }?.let {
                    Text("Incorrect answer. Choose again.", color = MaterialTheme.colorScheme.error)
                }
            }
            progress.task.options.forEach { option ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(
                        selected = progress.pendingAttempt?.selectedOptionId == option.id,
                        onClick = { onSelectOption(option.id) },
                        enabled = !isSubmitting
                    )
                    Text(option.text)
                }
            }
            Button(
                onClick = onSubmit,
                enabled = progress.pendingAttempt != null && !isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isSubmitting) "Submitting..." else "Submit answer") }
        }
    }
}