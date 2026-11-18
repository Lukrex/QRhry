package com.qrhry.app.data.online

import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageTask
import com.qrhry.app.domain.UserMessageException
import com.qrhry.app.domain.UserMessageKey
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class FirebaseCatalogRepository(private val app: FirebaseApp) : CatalogRepository {
    private val auth = FirebaseAuth.getInstance(app)
    private val firestore = FirebaseFirestore.getInstance(app)
    private val storage = FirebaseStorage.getInstance(app)
    private val functions = FirebaseFunctions.getInstance(app, FUNCTIONS_REGION)

    override val isConfigured: Boolean = true

    override fun currentAccount(): CatalogAccount? = auth.currentUser?.let {
        CatalogAccount(email = it.email, isEmailVerified = it.isEmailVerified)
    }

    override suspend fun search(query: String, cursor: CatalogPageCursor?): CatalogPage {
        try {
            var request: Query = firestore.collection(PUBLIC_GAMES)
                .whereEqualTo("state", PUBLISHED)
            val tokens = CatalogValidation.normalizeSearchTerm(query).split(' ').filter(String::isNotBlank)
            if (tokens.isNotEmpty()) request = request.whereArrayContainsAny("searchTokens", tokens)
            request = request.orderBy("publishedAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())
            cursor?.let {
                request = request.startAfter(Timestamp(Date(it.publishedAtMillis)), it.publicationId)
            }
            val snapshot = request.get().awaitResult()
            val games = snapshot.documents.map(::toCatalogGame)
            val last = snapshot.documents.lastOrNull()
            val next = if (snapshot.documents.size == PAGE_SIZE && last != null) {
                CatalogPageCursor(last.requiredTimestamp("publishedAt").toDate().time, last.id)
            } else null
            return CatalogPage(games, next)
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_NETWORK_FAILED)
        }
    }

    override suspend fun getGame(publicationId: String): CatalogGame? {
        try {
            if (!validId(publicationId)) return null
            val snapshot = firestore.collection(PUBLIC_GAMES).document(publicationId).get().awaitResult()
            if (!snapshot.exists() || snapshot.getString("state") != PUBLISHED) return null
            return toCatalogGame(snapshot)
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_NETWORK_FAILED)
        }
    }

    override suspend fun listMine(): List<OwnedCatalogPublication> {
        val user = auth.currentUser ?: throw UserMessageException(UserMessageKey.CATALOG_AUTH_REQUIRED)
        try {
            val snapshot = firestore.collection("users").document(user.uid)
                .collection("publications").orderBy("updatedAt", Query.Direction.DESCENDING).get().awaitResult()
            return snapshot.documents.map { document ->
                val game = toCatalogGame(document, publicationId = document.getString("publicationId") ?: document.id)
                OwnedCatalogPublication(game, document.getString("state") == PUBLISHED)
            }
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_NETWORK_FAILED)
        }
    }

    override suspend fun register(email: String, password: String): CatalogAccount {
        try {
            val user = auth.createUserWithEmailAndPassword(email.trim(), password).awaitResult().user
                ?: throw UserMessageException(UserMessageKey.CATALOG_SIGN_IN_FAILED)
            // Account creation has already succeeded at this point. A mail/link
            // delivery failure must not make registration appear to have failed
            // and leave the user unable to sign in to the newly created account.
            runCatching { user.sendEmailVerification().awaitResult() }
            return CatalogAccount(user.email, user.isEmailVerified)
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_SIGN_IN_FAILED)
        }
    }

    override suspend fun signIn(email: String, password: String): CatalogAccount {
        try {
            val user = auth.signInWithEmailAndPassword(email.trim(), password).awaitResult().user
                ?: throw UserMessageException(UserMessageKey.CATALOG_SIGN_IN_FAILED)
            return CatalogAccount(user.email, user.isEmailVerified)
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_SIGN_IN_FAILED)
        }
    }

    override suspend fun sendVerificationEmail() {
        val user = auth.currentUser ?: throw UserMessageException(UserMessageKey.CATALOG_AUTH_REQUIRED)
        try {
            user.sendEmailVerification().awaitResult()
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_VERIFICATION_FAILED)
        }
    }

    override suspend fun refreshAccount(): CatalogAccount? {
        val user = auth.currentUser ?: return null
        user.reload().awaitResult()
        return currentAccount()
    }

    override fun signOut() = auth.signOut()

    override suspend fun publish(
        archive: File,
        metadata: CatalogPublishMetadata,
        requestId: String,
        publicationId: String?,
        onProgress: (Float) -> Unit
    ): CatalogGame {
        val user = auth.currentUser ?: throw UserMessageException(UserMessageKey.CATALOG_AUTH_REQUIRED)
        user.reload().awaitResult()
        if (auth.currentUser?.isEmailVerified != true) {
            throw UserMessageException(UserMessageKey.CATALOG_EMAIL_UNVERIFIED)
        }
        if (CatalogValidation.validationError(metadata) != null) {
            throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        }
        if (!archive.isFile || archive.length() !in 1L..MAX_ARCHIVE_BYTES) {
            throw UserMessageException(UserMessageKey.CATALOG_UPLOAD_TOO_LARGE)
        }
        if (publicationId != null && !validId(publicationId)) {
            throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        }
        if (!validRevision(requestId)) throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        val prepared = try {
            functions.getHttpsCallable("beginPublicationUpload")
                .call(mapOf("requestId" to requestId)).awaitResult().getData() as? Map<*, *>
                ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        }
        if (prepared["alreadyPublished"] == true) {
            val id = prepared["publicationId"] as? String ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
            val published = getGame(id) ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
            onProgress(1f)
            return published
        }
        val staging = storage.reference.child("staging/${user.uid}/$requestId/$requestId.qrhry")
        try {
            val upload = staging.putFile(
                Uri.fromFile(archive),
                StorageMetadata.Builder().setContentType(ARCHIVE_MIME_TYPE).build()
            )
            upload.addOnProgressListener { task ->
                if (task.totalByteCount > 0L) onProgress(task.bytesTransferred.toFloat() / task.totalByteCount)
            }
            upload.awaitResult()
            onProgress(0.95f)
            val payload = hashMapOf<String, Any>(
                "requestId" to requestId,
                "description" to metadata.description.trim(),
                "languageTag" to metadata.languageTag.trim(),
                "authorName" to metadata.authorName.trim()
            )
            publicationId?.let { payload["publicationId"] = it }
            val result = functions.getHttpsCallable("publishGame").call(payload).awaitResult()
            val resultMap = result.getData() as? Map<*, *> ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
            val id = resultMap["publicationId"] as? String ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
            onProgress(1f)
            return getGame(id) ?: throw UserMessageException(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            runCatching { staging.delete().awaitResult() }
            throw exception.toCatalogError(UserMessageKey.CATALOG_PUBLICATION_FAILED)
        }
    }

    override suspend fun unpublish(publicationId: String) {
        if (!validId(publicationId)) throw UserMessageException(UserMessageKey.CATALOG_UNPUBLISH_FAILED)
        try {
            functions.getHttpsCallable("unpublishGame")
                .call(mapOf("publicationId" to publicationId)).awaitResult()
        } catch (exception: Exception) {
            throw exception.toCatalogError(UserMessageKey.CATALOG_UNPUBLISH_FAILED)
        }
    }

    override suspend fun download(game: CatalogGame, destination: File, onProgress: (Float) -> Unit) {
        if (!validId(game.publicationId) || !validRevision(game.revisionId)) {
            throw UserMessageException(UserMessageKey.CATALOG_DOWNLOAD_FAILED)
        }
        destination.parentFile?.mkdirs()
        destination.delete()
        try {
            val task = storage.reference.child("published/${game.publicationId}/${game.revisionId}.qrhry")
                .getFile(destination)
            task.addOnProgressListener { progress ->
                if (progress.totalByteCount > 0L) onProgress(progress.bytesTransferred.toFloat() / progress.totalByteCount)
            }
            task.awaitResult()
            if (destination.length() != game.archiveSize || withContext(Dispatchers.IO) { sha256(destination) } != game.archiveSha256) {
                throw UserMessageException(UserMessageKey.CATALOG_ARCHIVE_INVALID)
            }
        } catch (exception: Exception) {
            destination.delete()
            if (exception is CancellationException) throw exception
            throw exception.toCatalogError(UserMessageKey.CATALOG_DOWNLOAD_FAILED)
        }
    }

    private fun toCatalogGame(document: DocumentSnapshot, publicationId: String = document.id): CatalogGame {
        fun text(name: String, max: Int): String = document.getString(name)
            ?.takeIf { it.isNotBlank() && it.length <= max }
            ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        val revision = text("revisionId", 64)
        val language = text("languageTag", 35)
        val archiveSize = document.getLong("archiveSize")
            ?.takeIf { it in 1L..MAX_ARCHIVE_BYTES }
            ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        val checksum = text("archiveSha256", 64)
        val stations = document.getLong("stationCount")?.takeIf { it in 2..64 }
            ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        val tasks = document.getLong("taskCount")?.takeIf { it in 0..1024 }
            ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        val media = document.getLong("mediaCount")?.takeIf { it in 0..128 }
            ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        if (!validId(publicationId) || !validRevision(revision) || !Regex("[0-9a-f]{64}").matches(checksum) ||
            !Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(language)
        ) throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)
        return CatalogGame(
            publicationId = publicationId,
            revisionId = revision,
            title = text("title", 120),
            description = text("description", 500),
            authorName = text("authorName", 40),
            languageTag = language,
            stationCount = stations.toInt(),
            taskCount = tasks.toInt(),
            mediaCount = media.toInt(),
            archiveSize = archiveSize,
            archiveSha256 = checksum,
            publishedAtMillis = document.requiredTimestamp("publishedAt").toDate().time,
            updatedAtMillis = document.requiredTimestamp("updatedAt").toDate().time
        )
    }

    private fun DocumentSnapshot.requiredTimestamp(field: String): Timestamp = getTimestamp(field)
        ?: throw UserMessageException(UserMessageKey.CATALOG_METADATA_INVALID)

    private fun Throwable.toCatalogError(fallback: UserMessageKey): Throwable =
        if (this is UserMessageException || this is CancellationException) this else UserMessageException(fallback)

    private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{8,64}"))
    private fun validRevision(value: String) = value.matches(Regex("[A-Za-z0-9_-]{8,64}"))

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
        }

    private suspend fun StorageTask<*>.awaitResult() =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
            addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
            continuation.invokeOnCancellation { cancel() }
        }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
        private const val FUNCTIONS_REGION = "europe-west1"
        private const val PUBLIC_GAMES = "publicGames"
        private const val PUBLISHED = "PUBLISHED"
        private const val ARCHIVE_MIME_TYPE = "application/zip"
    }
}

object CatalogRepositoryFactory {
    fun create(): CatalogRepository? = runCatching {
        FirebaseCatalogRepository(FirebaseApp.getInstance())
    }.getOrNull()
}
