package com.qrhry.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.qrhry.app.domain.TaskScoringMode
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
import java.text.DateFormat
import java.util.Date
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
    val pointsText: String = "100",
    val options: List<TaskOptionFormState> = listOf(TaskOptionFormState(), TaskOptionFormState())
)

private fun MultipleChoiceTaskFormState.toDraft(position: Int) = MultipleChoiceTaskDraft(
    id = id,
    prompt = prompt,
    position = position,
    points = pointsText.toIntOrNull() ?: -1,
    options = options.mapIndexed { index, option ->
        TaskOptionDraft(option.id, option.text, index, option.isCorrect)
    }
)

private fun MultipleChoiceTask.toFormState() = MultipleChoiceTaskFormState(
    id = id,
    prompt = prompt,
    pointsText = points.toString(),
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
        val pointsText = next()
        val options = List(nextInt()) {
            TaskOptionFormState(
                id = next(),
                text = next(),
                isCorrect = next().toBoolean()
            )
        }
        return MultipleChoiceTaskFormState(taskId, prompt, pointsText, options)
    }

    fun nextRequiredTask(): MultipleChoiceTaskFormState = checkNotNull(nextTask())
}

private fun MutableList<String>.appendTask(task: MultipleChoiceTaskFormState?) {
    add(if (task == null) "0" else "1")
    if (task == null) return
    add(task.id)
    add(task.prompt)
    add(task.pointsText)
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
fun GameScreen(
    viewModel: GameViewModel,
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onLaunchQrScanner: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val saveableStateHolder = rememberSaveableStateHolder()
    var pendingPick by remember { mutableStateOf<PendingMediaPick?>(null) }
    var pendingExportGameId by remember { mutableStateOf<Long?>(null) }
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
    val archiveExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val gameId = pendingExportGameId
        pendingExportGameId = null
        if (uri != null && gameId != null) viewModel.exportGameArchive(gameId, uri)
    }
    val archiveImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.inspectGameArchive(uri)
        }
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
            isDarkMode = isDarkMode,
            onDarkModeChange = onDarkModeChange,
            onBack = viewModel::navigateBack
        )
        GameScreenRoute.CREATE -> GameLibraryScreen(
            state = state,
            viewModel = viewModel,
            onPickMedia = pickMedia,
            onExportGame = { game ->
                pendingExportGameId = game.id
                archiveExporter.launch(gameArchiveFilename(game.title))
            },
            onImportArchive = { archiveImporter.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        )
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
                taskSkippedFeedback = state.taskSkippedFeedback,
                onSelectOption = viewModel::selectTaskOption,
                onSubmitTask = viewModel::submitTaskAnswer,
                onContinueTask = viewModel::continueTaskAfterWrongAnswer,
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pinSettingsAtBottom = maxHeight >= 620.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (pinSettingsAtBottom) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                QRhryIcon(
                    QRhryIconKind.QR,
                    modifier = Modifier.padding(11.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HomeActionCard(
            title = stringResource(R.string.play),
            description = stringResource(R.string.home_play_description),
            icon = QRhryIconKind.PLAY,
            primary = true,
            onClick = onPlay
        )
        HomeActionCard(
            title = stringResource(R.string.home_create_title),
            description = stringResource(R.string.home_create_description),
            icon = QRhryIconKind.CREATE,
            primary = false,
            onClick = onCreate
        )

        if (pinSettingsAtBottom) Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onSettings),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                QRhryIcon(QRhryIconKind.SETTINGS, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                QRhryIcon(QRhryIconKind.FORWARD, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
}

@Composable
private fun HomeActionCard(
    title: String,
    description: String,
    icon: QRhryIconKind,
    primary: Boolean,
    onClick: () -> Unit
) {
    val background = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val foreground = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = foreground.copy(alpha = 0.12f)
            ) {
                QRhryIcon(icon, modifier = Modifier.padding(12.dp), tint = foreground)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = foreground)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (primary) foreground.copy(alpha = 0.84f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            QRhryIcon(QRhryIconKind.FORWARD, size = 20.dp, tint = foreground)
        }
    }
}

@Composable
private fun StatusMessage(
    message: String,
    isError: Boolean = false,
    icon: QRhryIconKind = if (isError) QRhryIconKind.CLOSE else QRhryIconKind.CHECK
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        QRhryIcon(icon, size = 20.dp, tint = color)
        Spacer(Modifier.width(8.dp))
        Text(message, color = color)
    }
}

@Composable
private fun SettingsScreen(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val darkModeLabel = stringResource(R.string.dark_mode)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                QRhryIcon(QRhryIconKind.SETTINGS, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = onBack) {
                IconLabel(QRhryIconKind.BACK, stringResource(R.string.back))
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    QRhryIcon(QRhryIconKind.LANGUAGE, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).selectable(
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
                    modifier = Modifier.fillMaxWidth().height(52.dp).selectable(
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    QRhryIcon(
                        if (isDarkMode) QRhryIconKind.THEME else QRhryIconKind.SUN,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(darkModeLabel)
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = darkModeLabel },
                    checked = isDarkMode,
                    onCheckedChange = onDarkModeChange
                )
            }
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
        UserMessageKey.TASK_POINTS_INVALID -> R.string.error_task_points
        UserMessageKey.GAME_UNAVAILABLE -> R.string.error_game_unavailable
        UserMessageKey.GAME_HAS_NO_STATIONS -> R.string.error_game_no_stations
        UserMessageKey.STATION_UNAVAILABLE -> R.string.error_station_unavailable
        UserMessageKey.SESSION_UNAVAILABLE -> R.string.error_session_unavailable
        UserMessageKey.SESSION_COMPLETED -> R.string.error_session_completed
        UserMessageKey.TASK_UNAVAILABLE -> R.string.error_task_unavailable
        UserMessageKey.TASK_NOT_CURRENT -> R.string.error_task_not_current
        UserMessageKey.TASK_ALREADY_COMPLETED -> R.string.error_task_completed
        UserMessageKey.TASK_CONTINUE_UNAVAILABLE -> R.string.error_task_continue_unavailable
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
        UserMessageKey.ARCHIVE_INVALID -> R.string.error_archive_invalid
        UserMessageKey.ARCHIVE_VERSION_UNSUPPORTED -> R.string.error_archive_version
        UserMessageKey.ARCHIVE_LIMIT_EXCEEDED -> R.string.error_archive_limit
        UserMessageKey.ARCHIVE_MEDIA_INVALID -> R.string.error_archive_media
        UserMessageKey.ARCHIVE_EXPORT_FAILED -> R.string.error_archive_export
        UserMessageKey.ARCHIVE_IMPORT_FAILED -> R.string.error_archive_import
    }
)

@Composable
private fun scoringModeLabel(mode: TaskScoringMode): String = stringResource(
    when (mode) {
        TaskScoringMode.EVENTUAL_CORRECT -> R.string.scoring_eventual_correct
        TaskScoringMode.FIRST_TRY_ONLY -> R.string.scoring_first_try_only
    }
)

private fun gameArchiveFilename(title: String): String {
    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(100)
        .ifBlank { "game" }
    return "$safeTitle.qrhry"
}

@Composable
private fun GameLibraryScreen(
    state: GameScreenState,
    viewModel: GameViewModel,
    onPickMedia: (StationMediaType, (Uri) -> Unit) -> Unit,
    onExportGame: (Game) -> Unit,
    onImportArchive: () -> Unit
) {
    var gameTitle by rememberSaveable { mutableStateOf("") }
    var scoringModeName by rememberSaveable { mutableStateOf(TaskScoringMode.EVENTUAL_CORRECT.name) }
    var stationForms by rememberSaveable(stateSaver = stationFormsSaver) {
        mutableStateOf(listOf(StationFormState(), StationFormState()))
    }

    LaunchedEffect(state.savedGameId) {
        if (state.savedGameId != null) {
            gameTitle = ""
            scoringModeName = TaskScoringMode.EVENTUAL_CORRECT.name
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
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    QRhryIcon(QRhryIconKind.CREATE, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.home_create_title),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(onClick = viewModel::navigateBack) {
                    IconLabel(QRhryIconKind.BACK, stringResource(R.string.home))
                }
            }
            OutlinedButton(
                onClick = viewModel::openScanner,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconLabel(QRhryIconKind.SCAN, stringResource(R.string.scan_station_qr))
            }
            OutlinedButton(
                onClick = onImportArchive,
                enabled = !state.isProcessingArchive,
                modifier = Modifier.fillMaxWidth()
            ) { IconLabel(QRhryIconKind.IMPORT, stringResource(R.string.import_game_archive)) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = gameTitle,
                        onValueChange = { gameTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.game_title)) },
                        singleLine = true
                    )
                    GameScoringModeSelector(
                        scoringMode = TaskScoringMode.valueOf(scoringModeName),
                        onScoringModeChange = { scoringModeName = it.name }
                    )
                }
            }

            stationForms.forEachIndexed { index, form ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            QRhryIcon(QRhryIconKind.LOCATION, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.station_number, index + 1),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
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
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                onPickMedia(StationMediaType.IMAGE) { uri ->
                                    stationForms = stationForms.updated(index) { it.copy(imageUri = uri) }
                                }
                            }, modifier = Modifier.weight(1f)) {
                                IconLabel(
                                    QRhryIconKind.IMAGE,
                                    stringResource(if (form.imageUri == null) R.string.choose_image else R.string.replace_image),
                                    iconSize = 18.dp,
                                    maxLines = 2
                                )
                            }
                            OutlinedButton(onClick = {
                                onPickMedia(StationMediaType.AUDIO) { uri ->
                                    stationForms = stationForms.updated(index) { it.copy(audioUri = uri) }
                                }
                            }, modifier = Modifier.weight(1f)) {
                                IconLabel(
                                    QRhryIconKind.AUDIO,
                                    stringResource(if (form.audioUri == null) R.string.choose_audio else R.string.replace_audio),
                                    iconSize = 18.dp,
                                    maxLines = 2
                                )
                            }
                        }
                        form.imageUri?.let { Text(stringResource(R.string.image_selected, it.lastPathSegment.orEmpty())) }
                        form.audioUri?.let { Text(stringResource(R.string.audio_selected, it.lastPathSegment.orEmpty())) }
                        if (form.task == null) {
                            OutlinedButton(onClick = {
                                stationForms = stationForms.updated(index) {
                                    it.copy(task = MultipleChoiceTaskFormState())
                                }
                            }) { IconLabel(QRhryIconKind.TASK, stringResource(R.string.add_multiple_choice_task)) }
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
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { stationForms = stationForms + StationFormState() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconLabel(QRhryIconKind.ADD, stringResource(R.string.add_station))
                }
                if (stationForms.size > 2) {
                    TextButton(
                        onClick = { stationForms = stationForms.dropLast(1) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        IconLabel(QRhryIconKind.REMOVE, stringResource(R.string.remove_last_station), MaterialTheme.colorScheme.error)
                    }
                }
            }

            state.error?.let { StatusMessage(userMessageText(it), isError = true) }
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
                        stationForms.map { it.imageUri to it.audioUri },
                        scoringMode = TaskScoringMode.valueOf(scoringModeName)
                    )
                },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconLabel(
                    QRhryIconKind.SAVE,
                    stringResource(if (state.isSaving) R.string.saving else R.string.save_game)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.saved_games), style = MaterialTheme.typography.titleLarge)
            if (state.isLoading && state.games.isEmpty()) {
                Text(stringResource(R.string.loading_saved_games))
            } else if (state.games.isEmpty()) {
                Text(stringResource(R.string.no_games_yet))
            } else {
                state.games.forEach { game ->
                    SavedGame(
                        game,
                        onEditStation = viewModel::editStation,
                        onScoringModeChange = viewModel::updateGameScoringMode,
                        onExport = onExportGame,
                        isExporting = state.isProcessingArchive
                    )
                }
            }
            if (state.isProcessingArchive) Text(stringResource(R.string.archive_processing))
            state.archiveSuccess?.let { success ->
                StatusMessage(
                    stringResource(
                        if (success == ArchiveUiSuccess.EXPORTED) R.string.archive_export_success
                        else R.string.archive_import_success
                    )
                )
            }
            state.archivePreview?.let { preview ->
                AlertDialog(
                    onDismissRequest = viewModel::cancelGameArchiveImport,
                    title = { Text(stringResource(R.string.archive_import_preview_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(
                                    R.string.archive_import_preview_body,
                                    preview.title,
                                    preview.stationCount,
                                    preview.taskCount,
                                    preview.mediaCount,
                                    scoringModeLabel(preview.scoringMode)
                                )
                            )
                            Text(stringResource(R.string.archive_import_identity_note))
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = viewModel::confirmGameArchiveImport,
                            enabled = !state.isProcessingArchive
                        ) { IconLabel(QRhryIconKind.IMPORT, stringResource(R.string.archive_import_confirm)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = viewModel::cancelGameArchiveImport,
                            enabled = !state.isProcessingArchive
                        ) { IconLabel(QRhryIconKind.CLOSE, stringResource(R.string.cancel)) }
                    }
                )
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                QRhryIcon(QRhryIconKind.PLAY, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.play), style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = viewModel::navigateBack) {
                IconLabel(QRhryIconKind.BACK, stringResource(R.string.home))
            }
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
                            IconLabel(QRhryIconKind.PLAY, stringResource(if (activeSession == null) R.string.start else R.string.continue_game))
                        }
                        val gameResults = state.completedSessions.filter { it.gameId == game.id }
                        if (gameResults.isNotEmpty()) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                QRhryIcon(QRhryIconKind.RESULTS, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.completed_runs), style = MaterialTheme.typography.titleSmall)
                            }
                            gameResults.forEach { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (result.totalTaskCount == null || result.totalStationCount == null) {
                                            stringResource(
                                                R.string.completed_run_legacy_summary,
                                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                                    .format(Date(result.completedAt))
                                            )
                                        } else {
                                            stringResource(
                                                R.string.completed_run_summary,
                                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                                    .format(Date(result.completedAt)),
                                                result.score
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { viewModel.openCompletedSession(result.sessionId) },
                                        enabled = !state.isLoadingSession
                                    ) { IconLabel(QRhryIconKind.RESULTS, stringResource(R.string.view_results), iconSize = 18.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { StatusMessage(userMessageText(it), isError = true) }
    }
}

@Composable
private fun PlaySessionScreen(
    progress: SessionProgress,
    scanResult: StationScanResult?,
    isLoading: Boolean,
    isSubmittingAnswer: Boolean,
    taskSkippedFeedback: Boolean,
    onSelectOption: (String, String) -> Unit,
    onSubmitTask: (String) -> Unit,
    onContinueTask: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    val acceptedScan = scanResult as? StationScanResult.Accepted
    val completedScanStation = acceptedScan?.station?.takeIf { it.id in progress.completedStationIds }
    val isCompleted = progress.session.status == GameSessionStatus.COMPLETED
    val displayedStation = if (isCompleted) null else progress.currentStation ?: completedScanStation
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) {
            IconLabel(QRhryIconKind.BACK, stringResource(R.string.back_to_play))
        }
        Text(progress.gameTitle, style = MaterialTheme.typography.headlineSmall)
        if (!isCompleted) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        QRhryIcon(QRhryIconKind.LOCATION, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.progress_count, progress.completedStationCount, progress.totalStationCount),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        QRhryIcon(QRhryIconKind.RESULTS, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.current_score,
                                progress.score.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                progress.score
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
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
        if (isCompleted) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                QRhryIcon(QRhryIconKind.CHECK, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.game_completed), style = MaterialTheme.typography.headlineSmall)
            }
            if (progress.resultTotalTaskCount == null || progress.resultTotalStationCount == null) {
                Text(stringResource(R.string.result_score_unavailable))
            } else {
                Text(
                    pluralStringResource(
                        R.plurals.result_score,
                        progress.score.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        progress.score
                    )
                )
            }
            Text(
                if (progress.resultTotalTaskCount == null) {
                    stringResource(R.string.result_tasks_total_unavailable, progress.completedTaskCount)
                } else {
                    stringResource(
                        R.string.result_tasks_completed,
                        progress.completedTaskCount,
                        progress.resultTotalTaskCount
                    )
                }
            )
            Text(
                if (progress.resultTotalStationCount == null) {
                    stringResource(R.string.result_stations_total_unavailable, progress.completedStationCount)
                } else {
                    stringResource(
                        R.string.result_stations_completed,
                        progress.completedStationCount,
                        progress.resultTotalStationCount
                    )
                }
            )
        } else if (progress.currentStation != null) {
            progress.taskProgress.forEach { taskProgress ->
                MultipleChoiceTaskPlayer(
                    progress = taskProgress,
                    isSubmitting = isSubmittingAnswer,
                    onSelectOption = { optionId -> onSelectOption(taskProgress.task.id, optionId) },
                    onSubmit = { onSubmitTask(taskProgress.task.id) },
                    onContinue = { onContinueTask(taskProgress.task.id) }
                )
            }
        } else if (progress.stationVisits.isEmpty()) {
            Text(stringResource(R.string.first_qr_intro))
            Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                IconLabel(QRhryIconKind.SCAN, stringResource(R.string.scan_first_qr))
            }
        } else {
            progress.nextStation?.let { next ->
                Text(stringResource(R.string.next_station), style = MaterialTheme.typography.titleMedium)
                Text(next.title, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onScan, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    IconLabel(QRhryIconKind.SCAN, stringResource(R.string.scan_station))
                }
            }
        }
        if (taskSkippedFeedback && progress.currentStation == null) {
            StatusMessage(stringResource(R.string.task_skipped), icon = QRhryIconKind.FORWARD)
        }
        scanResult?.let { result ->
            StatusMessage(
                stationScanMessage(result),
                isError = result !is StationScanResult.Accepted
            )
        }
    }
}

@Composable
private fun SavedGame(
    game: Game,
    onEditStation: (com.qrhry.app.domain.Station) -> Unit,
    onScoringModeChange: (Long, TaskScoringMode) -> Unit,
    onExport: (Game) -> Unit,
    isExporting: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(game.title, style = MaterialTheme.typography.titleMedium)
            GameScoringModeSelector(
                scoringMode = game.scoringMode,
                onScoringModeChange = { onScoringModeChange(game.id, it) }
            )
            OutlinedButton(
                onClick = { onExport(game) },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) { IconLabel(QRhryIconKind.EXPORT, stringResource(R.string.export_game_archive)) }
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
                    IconLabel(QRhryIconKind.EDIT, stringResource(R.string.edit_station_content))
                }
            }
        }
    }
}

@Composable
private fun GameScoringModeSelector(
    scoringMode: TaskScoringMode,
    onScoringModeChange: (TaskScoringMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    QRhryIcon(QRhryIconKind.RESULTS, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.game_scoring_mode), style = MaterialTheme.typography.titleSmall)
                }
        listOf(
            TaskScoringMode.EVENTUAL_CORRECT to R.string.scoring_eventual_correct,
            TaskScoringMode.FIRST_TRY_ONLY to R.string.scoring_first_try_only
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = scoringMode == mode,
                    onClick = { onScoringModeChange(mode) },
                    role = Role.RadioButton
                ),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    modifier = Modifier.padding(end = 8.dp),
                    selected = scoringMode == mode,
                    onClick = null
                )
                Text(stringResource(label))
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
    val isError = status == QrScanStatus.MALFORMED ||
        status == QrScanStatus.UNKNOWN || status == QrScanStatus.SCANNER_ERROR ||
        (sessionScanResult != null && sessionScanResult !is StationScanResult.Accepted)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            QRhryIcon(QRhryIconKind.SCAN, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scanner_heading), style = MaterialTheme.typography.headlineSmall)
        }
        val message = sessionScanResult?.let { stationScanMessage(it) } ?: when {
            isLookingUpStation -> stringResource(R.string.looking_up_station)
            status == QrScanStatus.MALFORMED -> stringResource(R.string.qr_malformed)
            status == QrScanStatus.UNKNOWN -> stringResource(R.string.qr_unknown)
            status == QrScanStatus.SCANNER_ERROR -> stringResource(R.string.scanner_failure)
            else -> stringResource(R.string.scan_guidance)
            }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                QRhryIcon(
                    if (isError) QRhryIconKind.CLOSE else QRhryIconKind.QR,
                    size = 20.dp,
                    tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Button(onClick = onScan, enabled = !isLookingUpStation, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            IconLabel(QRhryIconKind.SCAN, stringResource(R.string.open_camera_scanner))
        }
        OutlinedButton(onClick = onBack) {
            IconLabel(QRhryIconKind.BACK, stringResource(if (sessionScanResult != null) R.string.back_to_session else R.string.back))
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
            IconLabel(QRhryIconKind.BACK, stringResource(R.string.back_to_scanner))
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            QRhryIcon(QRhryIconKind.EDIT, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.edit_station), style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) })
        OutlinedTextField(bodyText, { draft = draft.copy(bodyText = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.body_text)) }, minLines = 3)
        Text(
            if (imageUri != null) stringResource(R.string.new_image_selected)
            else image?.originalFilename ?: stringResource(R.string.no_image)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.IMAGE) { draft = draft.copy(imageUri = it) } }, modifier = Modifier.weight(1f)) {
                IconLabel(
                    QRhryIconKind.IMAGE,
                    stringResource(if (image == null) R.string.choose_image else R.string.replace_image),
                    iconSize = 18.dp,
                    maxLines = 2
                )
            }
            if (image != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.IMAGE) }, modifier = Modifier.weight(1f)) {
                IconLabel(
                    QRhryIconKind.REMOVE,
                    stringResource(R.string.remove_image),
                    MaterialTheme.colorScheme.error,
                    iconSize = 18.dp,
                    maxLines = 2
                )
            }
        }
        Text(
            if (audioUri != null) stringResource(R.string.new_audio_selected)
            else audio?.originalFilename ?: stringResource(R.string.no_audio)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPickMedia(StationMediaType.AUDIO) { draft = draft.copy(audioUri = it) } }, modifier = Modifier.weight(1f)) {
                IconLabel(
                    QRhryIconKind.AUDIO,
                    stringResource(if (audio == null) R.string.choose_audio else R.string.replace_audio),
                    iconSize = 18.dp,
                    maxLines = 2
                )
            }
            if (audio != null) OutlinedButton(onClick = { onRemoveMedia(station.id, StationMediaType.AUDIO) }, modifier = Modifier.weight(1f)) {
                IconLabel(
                    QRhryIconKind.REMOVE,
                    stringResource(R.string.remove_audio),
                    MaterialTheme.colorScheme.error,
                    iconSize = 18.dp,
                    maxLines = 2
                )
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
                IconLabel(QRhryIconKind.TASK, stringResource(R.string.add_task))
            }
        }
        error?.let { StatusMessage(userMessageText(it), isError = true) }
        Button(
            onClick = {
                onSave(station.id, title, bodyText, imageUri, audioUri, tasks.mapIndexed { index, task ->
                    task.toDraft(index)
                })
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) { IconLabel(QRhryIconKind.SAVE, stringResource(if (isSaving) R.string.saving else R.string.save_station)) }
        OutlinedButton(onClick = onCancel) { IconLabel(QRhryIconKind.CLOSE, stringResource(R.string.cancel)) }
    }
}

@Composable
private fun StationImage(media: com.qrhry.app.domain.StationMedia) {
    if (!media.isAvailable || media.localPath == null) {
        StatusMessage(stringResource(R.string.image_unavailable, media.originalFilename ?: stringResource(R.string.stored_image)), isError = true)
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, media.localPath) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(media.localPath) }
    }
    if (bitmap == null) {
        StatusMessage(stringResource(R.string.image_load_failed, media.originalFilename ?: stringResource(R.string.stored_image)), isError = true)
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
        StatusMessage(stringResource(R.string.audio_unavailable, media.originalFilename ?: stringResource(R.string.stored_audio)), isError = true)
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
        StatusMessage(stringResource(R.string.audio_playback_failed, media.originalFilename ?: stringResource(R.string.stored_audio)), isError = true)
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
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    QRhryIcon(QRhryIconKind.TASK, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.multiple_choice_task), style = MaterialTheme.typography.titleMedium)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { IconLabel(QRhryIconKind.REMOVE, stringResource(R.string.remove_task), MaterialTheme.colorScheme.error) }
                }
            }
            OutlinedTextField(
                value = task.prompt,
                onValueChange = { onChange(task.copy(prompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.question)) },
                minLines = 3
            )
            OutlinedTextField(
                value = task.pointsText,
                onValueChange = { onChange(task.copy(pointsText = it)) },
                modifier = Modifier.align(Alignment.End).widthIn(min = 136.dp, max = 180.dp),
                label = { Text(stringResource(R.string.task_points)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    QRhryIcon(QRhryIconKind.TASK, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.answer_options), style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    stringResource(R.string.select_correct_answer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                task.options.forEachIndexed { index, option ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(
                                    modifier = Modifier.padding(end = 4.dp),
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        val options = task.options.toMutableList()
                                        val moved = options.removeAt(index)
                                        options.add(index - 1, moved)
                                        onChange(task.copy(options = options))
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.semantics {
                                        contentDescription = moveUpLabel
                                    }
                                ) {
                                    QRhryIcon(
                                        QRhryIconKind.UP,
                                        tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val options = task.options.toMutableList()
                                        val moved = options.removeAt(index)
                                        options.add(index + 1, moved)
                                        onChange(task.copy(options = options))
                                    },
                                    enabled = index < task.options.lastIndex,
                                    modifier = Modifier.semantics {
                                        contentDescription = moveDownLabel
                                    }
                                ) {
                                    QRhryIcon(
                                        QRhryIconKind.DOWN,
                                        tint = if (index < task.options.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { onChange(task.copy(options = task.options.filterIndexed { i, _ -> i != index })) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { IconLabel(QRhryIconKind.REMOVE, stringResource(R.string.remove_option), MaterialTheme.colorScheme.error, iconSize = 18.dp) }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onChange(task.copy(options = task.options + TaskOptionFormState())) },
                    modifier = Modifier.fillMaxWidth()
                ) { IconLabel(QRhryIconKind.ADD, stringResource(R.string.add_option)) }
            }
        }
    }
}

@Composable
private fun MultipleChoiceTaskPlayer(
    progress: TaskProgress,
    isSubmitting: Boolean,
    onSelectOption: (String) -> Unit,
    onSubmit: () -> Unit,
    onContinue: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(progress.task.prompt, style = MaterialTheme.typography.titleLarge)
            if (progress.isCompleted) {
                if (progress.isSkipped) {
                    StatusMessage(stringResource(R.string.task_skipped), icon = QRhryIconKind.FORWARD)
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        QRhryIcon(QRhryIconKind.CHECK, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.task_complete_points,
                                progress.awardedPoints,
                                progress.awardedPoints
                            )
                        )
                    }
                }
            } else {
                if (progress.pendingAttempt == null) {
                    progress.lastSubmittedAttempt?.takeIf { it.correctness == false }?.let {
                        StatusMessage(stringResource(R.string.incorrect_choose_again), isError = true)
                    }
                }
                progress.task.options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp).selectable(
                            selected = progress.pendingAttempt?.selectedOptionId == option.id,
                            onClick = { onSelectOption(option.id) },
                            enabled = !isSubmitting,
                            role = Role.RadioButton
                        ),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = progress.pendingAttempt?.selectedOptionId == option.id,
                            onClick = null,
                            enabled = !isSubmitting
                        )
                        Text(option.text)
                    }
                }
                Button(
                    onClick = onSubmit,
                    enabled = progress.pendingAttempt != null && !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                ) { IconLabel(QRhryIconKind.CHECK, stringResource(if (isSubmitting) R.string.submitting else R.string.submit_answer)) }
                if (progress.hasIncorrectSubmission) {
                    OutlinedButton(
                        onClick = onContinue,
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth()
                    ) { IconLabel(QRhryIconKind.FORWARD, stringResource(R.string.continue_without_correct)) }
                }
            }
        }
    }
}
