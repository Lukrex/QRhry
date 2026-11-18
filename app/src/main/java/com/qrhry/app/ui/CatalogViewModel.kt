package com.qrhry.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qrhry.app.data.online.CatalogAccount
import com.qrhry.app.data.online.CatalogGame
import com.qrhry.app.data.online.CatalogPageCursor
import com.qrhry.app.data.online.CatalogPublishMetadata
import com.qrhry.app.data.online.CatalogRepositoryFactory
import com.qrhry.app.data.online.OwnedCatalogPublication
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import com.qrhry.app.domain.messageKeyOr
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow

data class CatalogScreenState(
    val isConfigured: Boolean = false,
    val account: CatalogAccount? = null,
    val query: String = "",
    val games: List<CatalogGame> = emptyList(),
    val cursor: CatalogPageCursor? = null,
    val hasSearched: Boolean = false,
    val isLoading: Boolean = false,
    val selectedGame: CatalogGame? = null,
    val ownPublications: List<OwnedCatalogPublication> = emptyList(),
    val isWorking: Boolean = false,
    val progress: Float = 0f,
    val error: UserMessageKey? = null,
    val success: CatalogSuccess? = null,
    val email: String = "",
    val password: String = "",
    val isRegistering: Boolean = false,
    val publishGameId: Long? = null,
    val publishGameTitle: String = "",
    val editPublicationId: String? = null,
    val newPublicationId: String? = null,
    val publishRequestId: String? = null,
    val description: String = "",
    val languageTag: String = "sk",
    val authorName: String = ""
)

enum class CatalogSuccess { REGISTERED, SIGNED_IN, VERIFICATION_SENT, PUBLISHED, UNPUBLISHED, IMPORTED }

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CatalogRepositoryFactory.create()
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        CatalogScreenState(
            isConfigured = repository?.isConfigured == true,
            account = repository?.currentAccount()
        )
    )
    val state = mutableState.asStateFlow()
    private var currentOperation: Job? = null

    fun setQuery(query: String) { mutableState.value = mutableState.value.copy(query = query, error = null) }
    fun setCredentials(email: String, password: String) {
        mutableState.value = mutableState.value.copy(email = email, password = password, error = null)
    }
    fun setRegistering(value: Boolean) { mutableState.value = mutableState.value.copy(isRegistering = value, error = null) }
    fun setPublishMetadata(description: String, languageTag: String, authorName: String) {
        mutableState.value = mutableState.value.copy(
            description = description,
            languageTag = languageTag,
            authorName = authorName,
            error = null
        )
    }

    fun search(replace: Boolean = true) {
        val catalog = repository ?: return unavailable()
        if (mutableState.value.isLoading) return
        val current = mutableState.value
        mutableState.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val page = catalog.search(current.query, if (replace) null else current.cursor)
                mutableState.value = mutableState.value.copy(
                    games = if (replace) page.items else (mutableState.value.games + page.items).distinctBy { it.publicationId },
                    cursor = page.next,
                    hasSearched = true,
                    isLoading = false
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = exception.messageKeyOr(UserMessageKey.CATALOG_NETWORK_FAILED))
            }
        }
    }

    fun loadGame(publicationId: String) {
        val catalog = repository ?: return unavailable()
        mutableState.value = mutableState.value.copy(isLoading = true, selectedGame = null, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    selectedGame = catalog.getGame(publicationId),
                    isLoading = false,
                    error = null
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = exception.messageKeyOr(UserMessageKey.CATALOG_NETWORK_FAILED))
            }
        }
    }

    fun loadMine() {
        val catalog = repository ?: return unavailable()
        if (mutableState.value.account == null) return
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(ownPublications = catalog.listMine(), isLoading = false)
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = exception.messageKeyOr(UserMessageKey.CATALOG_NETWORK_FAILED))
            }
        }
    }

    fun authenticate() {
        val catalog = repository ?: return unavailable()
        val current = mutableState.value
        if (current.email.isBlank() || current.password.length < 6) {
            mutableState.value = current.copy(error = UserMessageKey.CATALOG_SIGN_IN_FAILED)
            return
        }
        if (current.isWorking) return
        mutableState.value = current.copy(isWorking = true, error = null, success = null)
        viewModelScope.launch {
            try {
                val account = if (current.isRegistering) catalog.register(current.email, current.password)
                else catalog.signIn(current.email, current.password)
                mutableState.value = mutableState.value.copy(
                    account = account,
                    isWorking = false,
                    success = if (current.isRegistering) CatalogSuccess.REGISTERED else CatalogSuccess.SIGNED_IN,
                    password = ""
                )
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isWorking = false,
                    error = exception.messageKeyOr(UserMessageKey.CATALOG_SIGN_IN_FAILED)
                )
            }
        }
    }

    fun refreshAccount() {
        val catalog = repository ?: return unavailable()
        viewModelScope.launch {
            try {
                mutableState.value = mutableState.value.copy(account = catalog.refreshAccount(), error = null)
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.messageKeyOr(UserMessageKey.CATALOG_SIGN_IN_FAILED))
            }
        }
    }

    fun sendVerification() {
        val catalog = repository ?: return unavailable()
        viewModelScope.launch {
            try {
                catalog.sendVerificationEmail()
                mutableState.value = mutableState.value.copy(success = CatalogSuccess.VERIFICATION_SENT, error = null)
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.messageKeyOr(UserMessageKey.CATALOG_SIGN_IN_FAILED))
            }
        }
    }

    fun signOut() {
        repository?.signOut()
        mutableState.value = mutableState.value.copy(
            account = null,
            ownPublications = emptyList(),
            success = null,
            error = null
        )
    }

    fun startPublishing(gameId: Long, title: String, publicationId: String? = null, game: CatalogGame? = null) {
        mutableState.value = mutableState.value.copy(
            publishGameId = gameId,
            publishGameTitle = title,
            editPublicationId = publicationId,
            newPublicationId = if (publicationId == null) java.util.UUID.randomUUID().toString() else null,
            publishRequestId = java.util.UUID.randomUUID().toString(),
            description = game?.description.orEmpty(),
            languageTag = game?.languageTag ?: "sk",
            authorName = game?.authorName.orEmpty(),
            error = null,
            success = null
        )
    }

    fun preparePublicationUpdate(publication: CatalogGame) {
        mutableState.value = mutableState.value.copy(
            publishGameId = null,
            publishGameTitle = "",
            editPublicationId = publication.publicationId,
            newPublicationId = null,
            publishRequestId = java.util.UUID.randomUUID().toString(),
            description = publication.description,
            languageTag = publication.languageTag,
            authorName = publication.authorName,
            error = null,
            success = null
        )
    }

    fun selectPublishGame(gameId: Long, title: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            publishGameId = gameId,
            publishGameTitle = title,
            newPublicationId = if (current.editPublicationId == null) current.newPublicationId ?: java.util.UUID.randomUUID().toString() else null,
            publishRequestId = current.publishRequestId ?: java.util.UUID.randomUUID().toString(),
            error = null
        )
    }

    fun clearPublishDraft() {
        mutableState.value = mutableState.value.copy(
            publishGameId = null, publishGameTitle = "", editPublicationId = null, newPublicationId = null, publishRequestId = null,
            description = "", languageTag = "sk", authorName = "", error = null
        )
    }

    fun publish(createArchive: suspend (Long) -> File) {
        val catalog = repository ?: return unavailable()
        val current = mutableState.value
        val gameId = current.publishGameId ?: return
        val metadata = CatalogPublishMetadata(current.description, current.languageTag, current.authorName)
        val error = com.qrhry.app.data.online.CatalogValidation.validationError(metadata)
        if (error != null) {
            mutableState.value = current.copy(error = UserMessageKey.CATALOG_METADATA_INVALID)
            return
        }
        if (current.isWorking) return
        currentOperation?.cancel()
        currentOperation = viewModelScope.launch {
            var archive: File? = null
            mutableState.value = mutableState.value.copy(isWorking = true, progress = 0f, error = null, success = null)
            try {
                archive = createArchive(gameId)
                catalog.publish(archive, metadata, current.publishRequestId ?: java.util.UUID.randomUUID().toString(), current.editPublicationId ?: current.newPublicationId) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress.coerceIn(0f, 1f))
                }
                mutableState.value = mutableState.value.copy(isWorking = false, progress = 1f, success = CatalogSuccess.PUBLISHED, newPublicationId = null, publishRequestId = null)
                loadMine()
            } catch (exception: Exception) {
                if (exception is CancellationException) return@launch
                mutableState.value = mutableState.value.copy(
                    isWorking = false,
                    error = exception.messageKeyOr(UserMessageKey.CATALOG_PUBLICATION_FAILED)
                )
            } finally {
                archive?.delete()
            }
        }
    }

    fun unpublish(publicationId: String) {
        val catalog = repository ?: return unavailable()
        if (mutableState.value.isWorking) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isWorking = true, error = null, success = null)
            try {
                catalog.unpublish(publicationId)
                mutableState.value = mutableState.value.copy(isWorking = false, success = CatalogSuccess.UNPUBLISHED)
                loadMine()
                search()
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    isWorking = false,
                    error = exception.messageKeyOr(UserMessageKey.CATALOG_UNPUBLISH_FAILED)
                )
            }
        }
    }

    fun downloadAndInspect(game: CatalogGame, inspect: suspend (File, String, String) -> Boolean) {
        val catalog = repository ?: return unavailable()
        if (mutableState.value.isWorking) return
        currentOperation?.cancel()
        currentOperation = viewModelScope.launch {
            val destination = File(getApplication<Application>().cacheDir, "catalog-${game.publicationId}-${game.revisionId}.qrhry")
            mutableState.value = mutableState.value.copy(isWorking = true, progress = 0f, error = null, success = null)
            try {
                catalog.download(game, destination) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress.coerceIn(0f, 1f))
                }
                val ready = inspect(destination, game.publicationId, game.revisionId)
                mutableState.value = mutableState.value.copy(
                    isWorking = false, progress = if (ready) 1f else 0f,
                    success = if (ready) CatalogSuccess.IMPORTED else null,
                    error = if (ready) null else UserMessageKey.CATALOG_ARCHIVE_INVALID
                )
            } catch (exception: Exception) {
                if (exception is CancellationException) return@launch
                mutableState.value = mutableState.value.copy(
                    isWorking = false,
                    error = exception.messageKeyOr(UserMessageKey.CATALOG_DOWNLOAD_FAILED)
                )
            } finally {
                destination.delete()
            }
        }
    }

    fun cancelOperation() {
        currentOperation?.cancel()
        currentOperation = null
        mutableState.value = mutableState.value.copy(isWorking = false, progress = 0f)
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(error = null, success = null) }
    fun consumeSuccess() { mutableState.value = mutableState.value.copy(success = null) }

    private fun unavailable() {
        mutableState.value = mutableState.value.copy(error = UserMessageKey.CATALOG_UNAVAILABLE)
    }
}
