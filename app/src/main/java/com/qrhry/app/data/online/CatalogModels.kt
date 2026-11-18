package com.qrhry.app.data.online

data class CatalogGame(
    val publicationId: String,
    val revisionId: String,
    val title: String,
    val description: String,
    val authorName: String,
    val languageTag: String,
    val stationCount: Int,
    val taskCount: Int,
    val mediaCount: Int,
    val archiveSize: Long,
    val archiveSha256: String,
    val publishedAtMillis: Long,
    val updatedAtMillis: Long
)

data class CatalogPageCursor(val publishedAtMillis: Long, val publicationId: String)
data class CatalogPage(val items: List<CatalogGame>, val next: CatalogPageCursor?)

data class CatalogPublishMetadata(
    val description: String,
    val languageTag: String,
    val authorName: String
)

data class CatalogAccount(
    val email: String?,
    val isEmailVerified: Boolean
)

data class OwnedCatalogPublication(
    val game: CatalogGame,
    val isPublished: Boolean
)

interface CatalogRepository {
    val isConfigured: Boolean
    fun currentAccount(): CatalogAccount?
    suspend fun search(query: String, cursor: CatalogPageCursor? = null): CatalogPage
    suspend fun getGame(publicationId: String): CatalogGame?
    suspend fun listMine(): List<OwnedCatalogPublication>
    suspend fun register(email: String, password: String): CatalogAccount
    suspend fun signIn(email: String, password: String): CatalogAccount
    suspend fun sendVerificationEmail()
    suspend fun refreshAccount(): CatalogAccount?
    fun signOut()
    suspend fun publish(
        archive: java.io.File,
        metadata: CatalogPublishMetadata,
        requestId: String,
        publicationId: String? = null,
        onProgress: (Float) -> Unit
    ): CatalogGame
    suspend fun unpublish(publicationId: String)
    suspend fun download(game: CatalogGame, destination: java.io.File, onProgress: (Float) -> Unit)
}
