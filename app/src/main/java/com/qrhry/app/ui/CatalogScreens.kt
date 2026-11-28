package com.qrhry.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.qrhry.app.R
import com.qrhry.app.data.online.CatalogGame
import com.qrhry.app.domain.UserMessageKey
import java.text.DateFormat
import java.util.Date

@Composable
fun CatalogScreen(
    gameState: GameScreenState,
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    openLocalGames: () -> Unit,
    createArchive: suspend (Long) -> java.io.File,
    inspectArchive: suspend (java.io.File, String, String) -> Boolean
) {
    val state by viewModel.state.collectAsState()
    var page by rememberSaveable { mutableStateOf("catalog") }
    var pendingUnpublish by remember { mutableStateOf<String?>(null) }
    var pendingPublish by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { if (state.isConfigured) viewModel.search() }
    LaunchedEffect(state.success) {
        if (state.success == CatalogSuccess.PUBLISHED || state.success == CatalogSuccess.UNPUBLISHED) {
            page = "mine"
            viewModel.consumeSuccess()
        }
    }
    BackHandler(enabled = page != "catalog") { page = "catalog"; viewModel.clearMessage() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QRhryIcon(QRhryIconKind.LOCATION, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = if (page == "catalog") onBack else { { page = "catalog"; viewModel.clearMessage() } }) {
                IconLabel(QRhryIconKind.BACK, stringResource(if (page == "catalog") R.string.back else R.string.catalog_back))
            }
        }
        if (!state.isConfigured) {
            Text(stringResource(R.string.catalog_not_configured), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else when (page) {
            "catalog" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query, onValueChange = viewModel::setQuery,
                        label = { Text(stringResource(R.string.catalog_search)) }, modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(onClick = { viewModel.search() }, enabled = !state.isLoading) { Text(stringResource(R.string.search)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page = "account" }) { Text(stringResource(if (state.account == null) R.string.catalog_sign_in else R.string.catalog_account)) }
                    if (state.account != null) OutlinedButton(onClick = { page = "mine"; viewModel.loadMine() }) { Text(stringResource(R.string.catalog_my_games)) }
                }
                if (state.isLoading) CircularProgressIndicator()
                if (state.hasSearched && state.games.isEmpty() && !state.isLoading) Text(stringResource(R.string.catalog_empty))
                state.games.forEach { game -> CatalogGameCard(game) { viewModel.loadGame(game.publicationId); page = "detail" } }
                if (state.cursor != null) OutlinedButton(onClick = { viewModel.search(replace = false) }, enabled = !state.isLoading) {
                    Text(stringResource(R.string.catalog_load_more))
                }
            }
            "detail" -> {
                val game = state.selectedGame
                if (state.isLoading) CircularProgressIndicator()
                if (game == null && !state.isLoading) Text(stringResource(R.string.catalog_missing))
                game?.let {
                    Text(it.title, style = MaterialTheme.typography.headlineSmall)
                    Text(it.description)
                    Text(stringResource(R.string.catalog_author, it.authorName))
                    Text(stringResource(R.string.catalog_language, it.languageTag))
                    Text(stringResource(R.string.catalog_published_date, DateFormat.getDateInstance().format(Date(it.publishedAtMillis))))
                    Text(stringResource(R.string.catalog_counts, it.stationCount, it.taskCount, it.mediaCount))
                    val imported = gameState.catalogImportedPublicationIds.contains(it.publicationId)
                    if (imported) {
                        Text(stringResource(R.string.catalog_already_imported), color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = openLocalGames) { Text(stringResource(R.string.catalog_back_to_games)) }
                    } else Button(onClick = {
                        viewModel.downloadAndInspect(it, inspectArchive)
                    }, enabled = !state.isWorking) { Text(stringResource(R.string.catalog_download_import)) }
                }
            }
            "account" -> {
                Text(stringResource(R.string.catalog_account_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(state.email, { viewModel.setCredentials(it, state.password) }, label = { Text(stringResource(R.string.catalog_email)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(state.password, { viewModel.setCredentials(state.email, it) }, label = { Text(stringResource(R.string.catalog_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::authenticate, enabled = !state.isWorking) { Text(stringResource(if (state.isRegistering) R.string.catalog_register else R.string.catalog_sign_in)) }
                    TextButton(onClick = { viewModel.setRegistering(!state.isRegistering) }) { Text(stringResource(if (state.isRegistering) R.string.catalog_existing_account else R.string.catalog_create_account)) }
                }
                state.account?.let { account ->
                    Text(account.email.orEmpty())
                    if (!account.isEmailVerified) {
                        Text(stringResource(R.string.catalog_verify_email), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::sendVerification) { Text(stringResource(R.string.catalog_send_verification)) }
                        OutlinedButton(onClick = viewModel::refreshAccount) { Text(stringResource(R.string.catalog_refresh_verification)) }
                    } else Text(stringResource(R.string.catalog_verified), color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { viewModel.signOut(); page = "catalog" }) { Text(stringResource(R.string.catalog_sign_out)) }
                    OutlinedButton(onClick = { page = "mine"; viewModel.loadMine() }) { Text(stringResource(R.string.catalog_my_games)) }
                }
            }
            "mine" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.catalog_my_games), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { viewModel.clearPublishDraft(); page = "publish" }) { Text(stringResource(R.string.catalog_publish)) }
                }
                if (state.account == null) Text(stringResource(R.string.catalog_sign_in_required))
                state.ownPublications.forEach { publication ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(publication.game.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (publication.isPublished) R.string.catalog_status_published else R.string.catalog_status_hidden))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    viewModel.preparePublicationUpdate(publication.game)
                                    page = "publish"
                                }) { Text(stringResource(R.string.catalog_update)) }
                                if (publication.isPublished) OutlinedButton(onClick = { pendingUnpublish = publication.game.publicationId }) { Text(stringResource(R.string.catalog_unpublish)) }
                            }
                        }
                    }
                }
                if (state.ownPublications.isEmpty() && !state.isLoading) Text(stringResource(R.string.catalog_no_publications))
            }
            "publish" -> {
                if (state.publishGameId == null) {
                    Text(stringResource(R.string.catalog_choose_game))
                    gameState.games.forEach { game ->
                        OutlinedButton(onClick = {
                            if (state.editPublicationId == null) viewModel.startPublishing(game.id, game.title)
                            else viewModel.selectPublishGame(game.id, game.title)
                        }, modifier = Modifier.fillMaxWidth()) { Text(game.title) }
                    }
                } else {
                    Text(stringResource(R.string.catalog_publish_summary, state.publishGameTitle), style = MaterialTheme.typography.titleMedium)
                    gameState.games.firstOrNull { it.id == state.publishGameId }?.let { game ->
                        Text(stringResource(R.string.catalog_publish_counts, game.stations.size, game.stations.sumOf { it.tasks.size }, game.stations.sumOf { it.media.size }))
                    }
                    OutlinedTextField(state.description, { viewModel.setPublishMetadata(it, state.languageTag, state.authorName) }, label = { Text(stringResource(R.string.catalog_description)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(state.languageTag, { viewModel.setPublishMetadata(state.description, it, state.authorName) }, label = { Text(stringResource(R.string.catalog_language_tag)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(state.authorName, { viewModel.setPublishMetadata(state.description, state.languageTag, it) }, label = { Text(stringResource(R.string.catalog_author_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text(stringResource(R.string.catalog_public_warning), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { pendingPublish = true }, enabled = !state.isWorking) { Text(stringResource(R.string.catalog_publish_confirm)) }
                    TextButton(onClick = { viewModel.clearPublishDraft(); page = "mine" }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
        state.error?.let { Text(catalogError(it), color = MaterialTheme.colorScheme.error) }
        state.success?.let { Text(stringResource(when (it) {
            CatalogSuccess.REGISTERED -> R.string.catalog_registered
            CatalogSuccess.SIGNED_IN -> R.string.catalog_signed_in
            CatalogSuccess.VERIFICATION_SENT -> R.string.catalog_verification_sent
            CatalogSuccess.PUBLISHED -> R.string.catalog_published
            CatalogSuccess.UNPUBLISHED -> R.string.catalog_unpublished
            CatalogSuccess.IMPORTED -> R.string.catalog_import_ready
        }), color = MaterialTheme.colorScheme.primary) }
        if (state.isWorking) {
            LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::cancelOperation) { Text(stringResource(R.string.cancel)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    pendingPublish?.let {
        AlertDialog(onDismissRequest = { pendingPublish = null },
            title = { Text(stringResource(R.string.catalog_publish_confirm)) },
            text = { Text(stringResource(R.string.catalog_public_warning)) },
            confirmButton = { TextButton(onClick = { pendingPublish = null; viewModel.publish(createArchive) }) { Text(stringResource(R.string.catalog_publish)) } },
            dismissButton = { TextButton(onClick = { pendingPublish = null }) { Text(stringResource(R.string.cancel)) } })
    }
    pendingUnpublish?.let { id ->
        AlertDialog(onDismissRequest = { pendingUnpublish = null },
            title = { Text(stringResource(R.string.catalog_unpublish)) },
            text = { Text(stringResource(R.string.catalog_unpublish_confirm)) },
            confirmButton = { TextButton(onClick = { pendingUnpublish = null; viewModel.unpublish(id) }) { Text(stringResource(R.string.catalog_unpublish)) } },
            dismissButton = { TextButton(onClick = { pendingUnpublish = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun CatalogGameCard(game: CatalogGame, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleMedium)
            Text(game.description, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.catalog_author, game.authorName), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.catalog_counts, game.stationCount, game.taskCount, game.mediaCount), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun catalogError(key: UserMessageKey): String = stringResource(when (key) {
    UserMessageKey.CATALOG_UNAVAILABLE -> R.string.catalog_error_unavailable
    UserMessageKey.CATALOG_NETWORK_FAILED -> R.string.catalog_error_network
    UserMessageKey.CATALOG_ARCHIVE_INVALID -> R.string.catalog_error_archive
    UserMessageKey.CATALOG_METADATA_INVALID -> R.string.catalog_error_metadata
    UserMessageKey.CATALOG_AUTH_REQUIRED -> R.string.catalog_error_auth
    UserMessageKey.CATALOG_EMAIL_UNVERIFIED -> R.string.catalog_error_unverified
    UserMessageKey.CATALOG_VERIFICATION_FAILED -> R.string.catalog_error_verification
    UserMessageKey.CATALOG_SIGN_IN_FAILED -> R.string.catalog_error_signin
    UserMessageKey.CATALOG_PUBLICATION_FAILED -> R.string.catalog_error_publish
    UserMessageKey.CATALOG_UNPUBLISH_FAILED -> R.string.catalog_error_unpublish
    UserMessageKey.CATALOG_ALREADY_IMPORTED -> R.string.catalog_error_duplicate
    UserMessageKey.CATALOG_DOWNLOAD_FAILED -> R.string.catalog_error_download
    UserMessageKey.CATALOG_UPLOAD_TOO_LARGE -> R.string.catalog_error_size
    else -> R.string.error_generic
})
