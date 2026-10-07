package com.qrhry.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import com.qrhry.app.domain.UserMessageKey
import com.qrhry.app.R
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

private data class StationEditorDraftState(
    val title: String,
    val bodyText: String,
    val imageUri: Uri?,
    val audioUri: Uri?,
    val tasks: List<MultipleChoiceTaskFormState>
)

private class DraftTokenReader(private val values: List<String>) {
    private var index = 0

    fun next(): String = values[index++]
    fun nextInt(): Int = next().toInt()
    fun nextUri(): Uri? = next().takeIf(String::isNotEmpty)?.let(Uri::parse)

    fun nextTask(): MultipleChoiceTaskFormState? {
        if (next() != "1") return null
        val taskId = next()
        val prompt = next()
        val options = List(nextInt()) {
            TaskOptionFormState(
                id = next(),
                text = next(),
                isCorrect = next().toBoolean()
            )
        }
        return MultipleChoiceTaskFormState(taskId, prompt, options)
    }

    fun nextRequiredTask(): MultipleChoiceTaskFormState = checkNotNull(nextTask())
}

private fun MutableList<String>.appendTask(task: MultipleChoiceTaskFormState?) {
    add(if (task == null) "0" else "1")
    if (task == null) return
    add(task.id)
    add(task.prompt)
    add(task.options.size.toString())
    task.options.forEach { option ->
        add(option.id)
        add(option.text)
        add(option.isCorrect.toString())
    }
}

private val stationFormsSaver = listSaver<List<StationFormState>, String>(
    save = { forms ->
        buildList {
            add(forms.size.toString())
            forms.forEach { form ->
                add(form.title)
                add(form.bodyText)
                add(form.imageUri?.toString().orEmpty())
                add(form.audioUri?.toString().orEmpty())
                appendTask(form.task)
            }
        }
    },
    restore = { values ->
        val reader = DraftTokenReader(values)
        List(reader.nextInt()) {
            StationFormState(
                title = reader.next(),
                bodyText = reader.next(),
                imageUri = reader.nextUri(),
                audioUri = reader.nextUri(),
                task = reader.nextTask()
            )
        }
    }
)

private val stationEditorDraftSaver = listSaver<StationEditorDraftState, String>(
    save = { draft ->
        buildList {
            add(draft.title)
            add(draft.bodyText)
            add(draft.imageUri?.toString().orEmpty())
            add(draft.audioUri?.toString().orEmpty())
            add(draft.tasks.size.toString())
            draft.tasks.forEach { appendTask(it) }
        }
    },
    restore = { values ->
        val reader = DraftTokenReader(values)
        StationEditorDraftState(
            title = reader.next(),
            bodyText = reader.next(),
            imageUri = reader.nextUri(),
            audioUri = reader.nextUri(),
            tasks = List(reader.nextInt()) { reader.nextRequiredTask() }
        )
    }
)

@Composable
fun GameScreen(viewModel: GameViewModel, onLaunchQrScanner: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val saveableStateHolder = rememberSaveableStateHolder()
    var pendingPick by remember { mutableStateOf<PendingMediaPick?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingPick
        pendingPick = null
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            target?.onPicked?.invoke(uri)
        }
    }
    val pickMedia: (StationMediaType, (Uri) -> Unit) -> Unit = { mediaType, callback ->
        pendingPick = PendingMediaPick(mediaType, callback)
        mediaPicker.launch(arrayOf(if (mediaType == StationMediaType.IMAGE) "image/*" else "audio/*"))
    }

    BackHandler(enabled = state.route != GameScreenRoute.HOME) {
        viewModel.navigateBack()
    }

    val routeKey = if (state.route == GameScreenRoute.EDIT_STATION) {
        "${state.route.name}:${state.editingStation?.id}"
    } else {
        state.route.name
    }
    saveableStateHolder.SaveableStateProvider(routeKey) {
    when (state.route) {
        GameScreenRoute.HOME -> HomeScreen(
            onCreate = viewModel::openCreate,
            onPlay = viewModel::openPlayList,
            onSettings = viewModel::openSettings
        )
        GameScreenRoute.SETTINGS -> SettingsScreen(
            selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "sk",
            onSelectLanguage = { language ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            },
            onBack = viewModel::navigateBack
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
}

@Composable
private fun HomeScreen(onCreate: () -> Unit, onPlay: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_choose_mode), style = MaterialTheme.typography.titleLarge)
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create_edit))
        }
        OutlinedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.play))
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings))
        }
    }
}

@Composable
private fun SettingsScreen(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = selectedLanguage == "sk",
                onClick = { onSelectLanguage("sk") },
                role = Role.RadioButton
            ),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            RadioButton(selected = selectedLanguage == "sk", onClick = null)
            Text(stringResource(R.string.language_slovenian))
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = selectedLanguage == "en",
                onClick = { onSelectLanguage("en") },
                role = Role.RadioButton
            ),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            RadioButton(selected = selectedLanguage == "en", onClick = null)
            Text(stringResource(R.string.language_english))
        }
    }
}

@Composable
private fun userMessageText(key: UserMessageKey): String = stringResource(
    when (key) {
        UserMessageKey.GENERIC_ERROR -> R.string.error_generic
        UserMessageKey.GAME_TITLE_REQUIRED -> R.string.error_game_title_required
        UserMessageKey.MINIMUM_STATIONS_REQUIRED -> R.string.error_minimum_stations
        UserMessageKey.STATION_TITLE_REQUIRED -> R.string.error_station_title_required
        UserMessageKey.TASK_ORDER_INVALID -> R.string.error_task_order
        UserMessageKey.TASK_ID_INVALID -> R.string.error_task_identity
        UserMessageKey.QUESTION_REQUIRED -> R.string.error_question_required
        UserMessageKey.MINIMUM_OPTIONS_REQUIRED -> R.string.error_minimum_options
        UserMessageKey.OPTION_ORDER_INVALID -> R.string.error_option_order
        UserMessageKey.OPTION_TEXT_REQUIRED -> R.string.error_option_text_required
        UserMessageKey.EXACTLY_ONE_CORRECT_OPTION_REQUIRED -> R.string.error_one_correct_option
        UserMessageKey.OPTION_ID_INVALID -> R.string.error_option_identity
        UserMessageKey.GAME_UNAVAILABLE -> R.string.error_game_unavailable
        UserMessageKey.GAME_HAS_NO_STATIONS -> R.string.error_game_no_stations
        UserMessageKey.STATION_UNAVAILABLE -> R.string.error_station_unavailable
        UserMessageKey.SESSION_UNAVAILABLE -> R.string.error_session_unavailable
        UserMessageKey.SESSION_COMPLETED -> R.string.error_session_completed
        UserMessageKey.TASK_UNAVAILABLE -> R.string.error_task_unavailable
        UserMessageKey.TASK_NOT_CURRENT -> R.string.error_task_not_current
        UserMessageKey.TASK_ALREADY_COMPLETED -> R.string.error_task_completed
        UserMessageKey.ANSWER_UNAVAILABLE -> R.string.error_answer_unavailable
        UserMessageKey.SELECT_ANSWER_FIRST -> R.string.error_select_answer
        UserMessageKey.TASK_EDIT_ACTIVE_SESSION -> R.string.error_task_edit_active
        UserMessageKey.TASK_DELETE_HAS_ATTEMPTS -> R.string.error_task_delete_attempts
        UserMessageKey.MEDIA_GAME_ID_INVALID -> R.string.error_media_game_identity
        UserMessageKey.MEDIA_TYPE_UNKNOWN -> R.string.error_media_type_unknown
        UserMessageKey.MEDIA_TYPE_UNSUPPORTED -> R.string.error_media_type_unsupported
        UserMessageKey.MEDIA_OPEN_FAILED -> R.string.error_media_open
        UserMessageKey.MEDIA_EMPTY -> R.string.error_media_empty
        UserMessageKey.MEDIA_STORE_FAILED -> R.string.error_media_store
        UserMessageKey.MEDIA_PATH_INVALID -> R.string.error_media_path
        UserMessageKey.GAME_SAVE_FAILED -> R.string.error_game_save
        UserMessageKey.STATION_SAVE_FAILED -> R.string.error_station_save
        UserMessageKey.ANSWER_SELECT_FAILED -> R.string.error_answer_select
        UserMessageKey.ANSWER_SUBMIT_FAILED -> R.string.error_answer_submit
        UserMessageKey.GAMES_LOAD_FAILED -> R.string.error_games_load
        UserMessageKey.SESSION_START_FAILED -> R.string.error_session_start
        UserMessageKey.SCANNER_LOOKUP_FAILED -> R.string.error_scanner_lookup
    }
)

@Composable
private fun GameLibraryScreen(
    state: GameScreenState,
    viewModel: GameViewModel,
    onPickMedia: (StationMediaType, (Uri) -> Unit) -> Unit
) {
    var gameTitle by rememberSaveable { mutableStateOf("") }
    var stationForms by rememberSaveable(stateSaver = stationFormsSaver) {
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
                    Text(stringResource(R.string.create_edit), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = viewModel::navigateBack) { Text(stringResource(R.string.home)) }
                }
            OutlinedButton(
                onClick = viewModel::openScanner,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.scan_station_qr))
            }
            OutlinedTextField(
                value = gameTitle,
                onValueChange = { gameTitle = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.game_title)) },
                singleLine = true
            )

            stationForms.forEachIndexed { index, form ->
                Text(stringResource(R.string.station_number, index + 1), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { value -> stationForms = stationForms.updated(index) { it.copy(title = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.station_title)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.bodyText,
                    onValueChange = { value -> stationForms = stationForms.updated(index) { it.copy(bodyText = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.station_text)) },
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        onPickMedia(StationMediaType.IMAGE) { uri ->
                            stationForms = stationForms.updated(index) { it.copy(imageUri = uri) }
                        }
                    }) {
                        Text(stringResource(if (form.imageUri == null) R.string.choose_image else R.string.replace_image))
                    }
                    OutlinedButton(onClick = {
                        onPickMedia(StationMediaType.AUDIO) { uri ->
                            stationForms = stationForms.updated(index) { it.copy(audioUri = uri) }
                        }
                    }) {
                        Text(stringResource(if (form.audioUri == null) R.string.choose_audio else R.string.replace_audio))
                    }
                }
                form.imageUri?.let { Text(stringResource(R.string.image_selected, it.lastPathSegment.orEmpty())) }
                form.audioUri?.let { Text(stringResource(R.string.audio_selected, it.lastPathSegment.orEmpty())) }
                if (form.task == null) {
                    OutlinedButton(onClick = {
                        stationForms = stationForms.updated(index) {
                            it.copy(task = MultipleChoiceTaskFormState())
                        }
                    }) { Text(stringResource(R.string.add_multiple_choice_task)) }
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
                    Text(stringResource(R.string.add_station))
                }
                if (stationForms.size > 2) {
                    OutlinedButton(
                        onClick = { stationForms = stationForms.dropLast(1) }
                    ) {
                        Text(stringResource(R.string.remove_last_station))
                    }
                }
            }

            state.error?.let { Text(userMessageText(it), color = MaterialTheme.colorScheme.error) }
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
                Text(stringResource(if (state.isSaving) R.string.saving else R.string.save_game))
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.saved_games), style = MaterialTheme.typography.titleLarge)
            if (state.isLoading && state.games.isEmpty()) {
                Text(stringResource(R.string.loading_saved_games))
            } else if (state.games.isEmpty()) {
                Text(stringResource(R.string.no_games_yet))
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
            Text(stringResource(R.string.play), style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = viewModel::navigateBack) { Text(stringResource(R.string.home)) }
        }
        when {
            state.isLoading && state.games.isEmpty() -> Text(stringResource(R.string.loading_games))
            state.games.isEmpty() -> Text(stringResource(R.string.empty_game_list))
            else -> state.games.forEach { game ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(game.title, style = MaterialTheme.typography.titleMedium)
                        Text(pluralStringResource(R.plurals.station_count, game.stations.size, game.stations.size))
                        val activeSession = state.activeSessions[game.id]
                        Button(
                            onClick = { viewModel.startOrResumeSession(game.id) },
                            enabled = !state.isLoadingSession,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(if (activeSession == null) R.string.start else R.string.continue_game))
                        }
                    }
                }
            }
        }
        state.error?.let { Text(userMessageText(it), color = MaterialTheme.colorScheme.error) }
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
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back_to_play)) }
        Text(progress.gameTitle, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.progress_count, progress.completedStationCount, progress.totalStationCount),
            style = MaterialTheme.typography.titleMedium
        )
        displayedStation?.let { station ->
            Text(
                stringResource(
                    if (station.id in progress.completedStationIds) R.string.completed_station
                    else R.string.current_station
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Text(station.title, style = MaterialTheme.typography.headlineSmall)
            if (station.bodyText.isNotBlank()) Text(station.bodyText)
            station.media.filter { it.mediaType == StationMediaType.IMAGE }.forEach { StationImage(it) }
            station.media.filter { it.mediaType == StationMediaType.AUDIO }.forEach { StationAudioPlayer(it) }
        }
        if (progress.session.status == GameSessionStatus.COMPLETED) {
            Text(stringResource(R.string.game_completed), style = MaterialTheme.typography.headlineSmall)
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
            Text(stringResource(R.string.first_qr_intro))
            Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_first_qr))
            }
        } else {
            progress.nextStation?.let { next ->
                Text(stringResource(R.string.next_station), style = MaterialTheme.typography.titleMedium)
                Text(next.title, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_station))
                }
            }
        }
        if (progress.currentStation == null) {
            taskSubmissionCorrect?.let { correct ->
                Text(stringResource(if (correct) R.string.correct_answer else R.string.incorrect_try_again))
            }
        }
        scanResult?.let { result ->
            Text(
                stationScanMessage(result),
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
                Text(stringResource(R.string.station_index_title, index + 1, station.title), style = MaterialTheme.typography.bodyMedium)
                if (station.bodyText.isNotBlank()) {
                    Text(station.bodyText, style = MaterialTheme.typography.bodySmall)
                }
                StationQrImage(
                    station = station,
                    modifier = Modifier.size(144.dp)
                )
                OutlinedButton(onClick = { onEditStation(station) }) {
                    Text(stringResource(R.string.edit_station_content))
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
        Text(stringResource(R.string.scanner_heading), style = MaterialTheme.typography.headlineSmall)
        val message = sessionScanResult?.let { stationScanMessage(it) } ?: when {
            isLookingUpStation -> stringResource(R.string.looking_up_station)
            status == QrScanStatus.MALFORMED -> stringResource(R.string.qr_malformed)
            status == QrScanStatus.UNKNOWN -> stringResource(R.string.qr_unknown)
            status == QrScanStatus.SCANNER_ERROR -> stringResource(R.string.scanner_failure)
            else -> stringResource(R.string.scan_guidance)
            }
        Text(
            message,
            color = if (status == QrScanStatus.MALFORMED ||
                status == QrScanStatus.UNKNOWN || status == QrScanStatus.SCANNER_ERROR ||
                (sessionScanResult != null && sessionScanResult !is StationScanResult.Accepted)
            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Button(onClick = onScan, enabled = !isLookingUpStation) {
            Text(stringResource(R.string.open_camera_scanner))
        }
        OutlinedButton(onClick = onBack) {
            Text(stringResource(if (sessionScanResult != null) R.string.back_to_session else R.string.back))
        }
    }
}

@Composable
private fun stationScanMessage(result: StationScanResult): String = when (result) {
    is StationScanResult.Accepted -> when {
        result.progress.currentStation?.id == result.station.id -> stringResource(
            if (result.resumed) R.string.scan_accepted_resumed else R.string.scan_accepted_visited,
            result.station.title
        )
        result.progress.session.status == GameSessionStatus.COMPLETED ->
            stringResource(R.string.scan_game_finished, result.station.title)
        else -> stringResource(R.string.scan_next_station, result.station.title)
    }
    StationScanResult.UnknownQr -> stringResource(R.string.qr_unknown)
    is StationScanResult.WrongGame -> stringResource(R.string.scan_wrong_game, result.station.title)
    is StationScanResult.OutOfOrder -> stringResource(R.string.scan_out_of_order, result.expectedStation.title)
    is StationScanResult.AlreadyVisited -> stringResource(R.string.scan_already_visited, result.station.title)
    StationScanResult.SessionCompleted -> stringResource(R.string.scan_session_completed)
    StationScanResult.SessionUnavailable -> stringResource(R.string.scan_session_unavailable)
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
        Text(lookup.station.bodyText.ifBlank { stringResource(R.string.station_no_text) })
        lookup.station.media.filter { it.mediaType == StationMediaType.IMAGE }.forEach { StationImage(it) }
        lookup.station.media.filter { it.mediaType == StationMediaType.AUDIO }.forEach { StationAudioPlayer(it) }
        StationQrImage(lookup.station, Modifier.size(224.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.back_to_scanner))
        }
    }
}

@Composable
private fun StationEditScreen(
    station: com.qrhry.app.domain.Station,
    isSaving: Boolean,
    error: UserMessageKey?,
    onPickMedia: (StationMediaType, (Uri) -> Unit) -> Unit,
    onRemoveMedia: (Long, StationMediaType) -> Unit,
    onSave: (Long, String, String, Uri?, Uri?, List<MultipleChoiceTaskDraft>) -> Unit,
    onCancel: () -> Unit
) {
    var draft by rememberSaveable(station.id, stateSaver = stationEditorDraftSaver) {
        mutableStateOf(
            StationEditorDraftState(
                title = station.title,
                bodyText = station.bodyText,
                imageUri = null,
                audioUri = null,
                tasks = station.tasks.map { it.toFormState() }
            )
        )
    }
    val title = draft.title
    val bodyText = draft.bodyText
    val imageUri = draft.imageUri
    val audioUri = draft.audioUri
    val tasks = draft.tasks
    val image = station.media.firstOrNull { it.mediaType == StationMediaType.IMAGE }
    val audio = station.media.firstOrNull { it.mediaType == StationMediaType.AUDIO }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.edit_station), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) })
        OutlinedTextField(bodyText, { draft = draft.copy(bodyText = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.body_text)) }, minLines = 3)
        Text(
            if (imageUri != null) stringResource(R.string.new_image_selected)
            else image?.originalFilename ?: stringResource(R.string.no_image)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.IMAGE) { draft = draft.copy(imageUri = it) } }) {
                Text(stringResource(if (image == null) R.string.choose_image else R.string.replace_image))
            }
            if (image != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.IMAGE) }) {
                Text(stringResource(R.string.remove_image))
            }
        }
        Text(
            if (audioUri != null) stringResource(R.string.new_audio_selected)
            else audio?.originalFilename ?: stringResource(R.string.no_audio)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.AUDIO) { draft = draft.copy(audioUri = it) } }) {
                Text(stringResource(if (audio == null) R.string.choose_audio else R.string.replace_audio))
            }
            if (audio != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.AUDIO) }) {
                Text(stringResource(R.string.remove_audio))
            }
        }
        tasks.forEachIndexed { index, task ->
            MultipleChoiceTaskEditor(
                task = task,
                onChange = { updated -> draft = draft.copy(tasks = tasks.updated(index) { updated }) },
                onRemove = { draft = draft.copy(tasks = tasks.filterIndexed { taskIndex, _ -> taskIndex != index }) }
            )
        }
        if (tasks.isEmpty()) {
            OutlinedButton(onClick = { draft = draft.copy(tasks = listOf(MultipleChoiceTaskFormState())) }) {
                Text(stringResource(R.string.add_task))
            }
        }
        error?.let { Text(userMessageText(it), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                onSave(station.id, title, bodyText, imageUri, audioUri, tasks.mapIndexed { index, task ->
                    task.toDraft(index)
                })
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(if (isSaving) R.string.saving else R.string.save_station)) }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun StationImage(media: com.qrhry.app.domain.StationMedia) {
    if (!media.isAvailable || media.localPath == null) {
        Text(stringResource(R.string.image_unavailable, media.originalFilename ?: stringResource(R.string.stored_image)))
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, media.localPath) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(media.localPath) }
    }
    if (bitmap == null) {
        Text(stringResource(R.string.image_load_failed, media.originalFilename ?: stringResource(R.string.stored_image)))
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = media.originalFilename ?: stringResource(R.string.station_image_description),
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
        Text(stringResource(R.string.audio_unavailable, media.originalFilename ?: stringResource(R.string.stored_audio)))
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
        Text(stringResource(R.string.audio_playback_failed, media.originalFilename ?: stringResource(R.string.stored_audio)))
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
        contentDescription = stringResource(R.string.station_qr_description, station.title),
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
            Text(stringResource(R.string.multiple_choice_task), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.remove_task)) }
        }
        OutlinedTextField(
            value = task.prompt,
            onValueChange = { onChange(task.copy(prompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.question)) }
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
                    label = { Text(stringResource(R.string.option_number, index + 1)) },
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
                ) { Text(stringResource(R.string.move_up)) }
                OutlinedButton(
                    onClick = {
                        val options = task.options.toMutableList()
                        val moved = options.removeAt(index)
                        options.add(index + 1, moved)
                        onChange(task.copy(options = options))
                    },
                    enabled = index < task.options.lastIndex
                ) { Text(stringResource(R.string.move_down)) }
                OutlinedButton(
                    onClick = { onChange(task.copy(options = task.options.filterIndexed { i, _ -> i != index })) }
                ) { Text(stringResource(R.string.remove_option)) }
            }
        }
        OutlinedButton(
            onClick = { onChange(task.copy(options = task.options + TaskOptionFormState())) }
        ) { Text(stringResource(R.string.add_option)) }
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
            Text(stringResource(R.string.task_complete))
        } else {
            if (progress.pendingAttempt == null) {
                progress.lastSubmittedAttempt?.takeIf { it.correctness == false }?.let {
                    Text(stringResource(R.string.incorrect_choose_again), color = MaterialTheme.colorScheme.error)
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
            ) { Text(stringResource(if (isSubmitting) R.string.submitting else R.string.submit_answer)) }
        }
    }
}