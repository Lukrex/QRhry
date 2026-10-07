package com.qrhry.app.domain

import java.util.UUID

object GameDraftValidator {
    fun validationError(draft: GameDraft): UserMessageKey? = when {
        draft.title.isBlank() -> UserMessageKey.GAME_TITLE_REQUIRED
        draft.stations.size < 2 -> UserMessageKey.MINIMUM_STATIONS_REQUIRED
        draft.stations.any { it.title.isBlank() } -> UserMessageKey.STATION_TITLE_REQUIRED
        draft.stations.any { station -> taskValidationError(station.tasks) != null } ->
            draft.stations.firstNotNullOfOrNull { taskValidationError(it.tasks) }
        else -> null
    }

    fun taskValidationError(tasks: List<MultipleChoiceTaskDraft>): UserMessageKey? {
        if (tasks.map { it.position } != tasks.indices.toList()) return UserMessageKey.TASK_ORDER_INVALID
        tasks.forEach { task ->
            if (!isCanonicalUuid(task.id)) return UserMessageKey.TASK_ID_INVALID
            if (task.prompt.isBlank()) return UserMessageKey.QUESTION_REQUIRED
            if (task.options.size < 2) return UserMessageKey.MINIMUM_OPTIONS_REQUIRED
            if (task.options.map { it.position } != task.options.indices.toList()) {
                return UserMessageKey.OPTION_ORDER_INVALID
            }
            if (task.options.any { it.text.isBlank() }) return UserMessageKey.OPTION_TEXT_REQUIRED
            if (task.options.count { it.isCorrect } != 1) return UserMessageKey.EXACTLY_ONE_CORRECT_OPTION_REQUIRED
            if (task.options.any { !isCanonicalUuid(it.id) }) return UserMessageKey.OPTION_ID_INVALID
        }
        return null
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)
}