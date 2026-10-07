package com.qrhry.app.domain

object GameDraftValidator {
    fun validationError(draft: GameDraft): String? = when {
        draft.title.isBlank() -> "Enter a game title."
        draft.stations.size < 2 -> "Add at least two stations."
        draft.stations.any { it.title.isBlank() } -> "Enter a title for every station."
        else -> null
    }
}