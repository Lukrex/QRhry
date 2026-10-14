package com.qrhry.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class GameDraftValidatorTest {
    @Test
    fun acceptsGameWithTwoTitledStations() {
        val draft = GameDraft(
            title = "Campus trail",
            stations = listOf(
                StationDraft("Start", "Welcome"),
                StationDraft("Library", "Find the entrance")
            )
        )

        assertNull(GameDraftValidator.validationError(draft))
    }

    @Test
    fun rejectsBlankGameTitle() {
        val draft = GameDraft(
            title = " ",
            stations = listOf(StationDraft("Start", ""), StationDraft("End", ""))
        )

        assertEquals(UserMessageKey.GAME_TITLE_REQUIRED, GameDraftValidator.validationError(draft))
    }

    @Test
    fun requiresAtLeastTwoStations() {
        val draft = GameDraft("Campus trail", listOf(StationDraft("Start", "")))

        assertEquals(UserMessageKey.MINIMUM_STATIONS_REQUIRED, GameDraftValidator.validationError(draft))
    }

    @Test
    fun rejectsUntitledStation() {
        val draft = GameDraft(
            title = "Campus trail",
            stations = listOf(StationDraft("Start", ""), StationDraft(" ", ""))
        )

        assertEquals(
            UserMessageKey.STATION_TITLE_REQUIRED,
            GameDraftValidator.validationError(draft)
        )
    }

    @Test
    fun validatesEachNewTaskTypeAndTextAnswerNormalization() {
        val trueFalse = TrueFalseTaskDraft(UUID.randomUUID().toString(), "Statement", 0, true, 10)
        val shortText = ShortTextTaskDraft(UUID.randomUUID().toString(), "Question", 0, "café", 10)
        assertNull(GameDraftValidator.taskValidationError(listOf(trueFalse)))
        assertNull(GameDraftValidator.taskValidationError(listOf(shortText)))
        assertEquals(
            UserMessageKey.TEXT_ANSWER_REQUIRED,
            GameDraftValidator.taskValidationError(listOf(shortText.copy(correctAnswer = " ")))
        )
        assertEquals("café", ShortAnswerEvaluator.normalize("  CAFE\u0301  "))
        assertEquals("cafe", ShortAnswerEvaluator.normalize(" CAFE "))
        assertEquals(false, ShortAnswerEvaluator.matches("café", "cafe"))
        assertEquals(true, ShortAnswerEvaluator.matches("ŽILINA", "  žilina  "))
    }
}
