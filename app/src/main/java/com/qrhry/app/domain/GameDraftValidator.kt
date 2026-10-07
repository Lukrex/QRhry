package com.qrhry.app.domain

import java.util.UUID

object GameDraftValidator {
    fun validationError(draft: GameDraft): String? = when {
        draft.title.isBlank() -> "Enter a game title."
        draft.stations.size < 2 -> "Add at least two stations."
        draft.stations.any { it.title.isBlank() } -> "Enter a title for every station."
        draft.stations.any { station -> taskValidationError(station.tasks) != null } ->
            draft.stations.firstNotNullOfOrNull { taskValidationError(it.tasks) }
        else -> null
    }

    fun taskValidationError(tasks: List<MultipleChoiceTaskDraft>): String? {
        if (tasks.map { it.position } != tasks.indices.toList()) return "Task order is invalid."
        tasks.forEach { task ->
            if (!isCanonicalUuid(task.id)) return "Task identity is invalid."
            if (task.prompt.isBlank()) return "Enter a question."
            if (task.options.size < 2) return "Add at least two answer options."
            if (task.options.map { it.position } != task.options.indices.toList()) {
                return "Answer option order is invalid."
            }
            if (task.options.any { it.text.isBlank() }) return "Enter text for every answer option."
            if (task.options.count { it.isCorrect } != 1) return "Select exactly one correct answer."
            if (task.options.any { !isCanonicalUuid(it.id) }) return "Answer option identity is invalid."
        }
        return null
    }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)
}