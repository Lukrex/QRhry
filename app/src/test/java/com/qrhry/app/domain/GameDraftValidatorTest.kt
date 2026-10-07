package com.qrhry.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        assertEquals("Enter a game title.", GameDraftValidator.validationError(draft))
    }

    @Test
    fun requiresAtLeastTwoStations() {
        val draft = GameDraft("Campus trail", listOf(StationDraft("Start", "")))

        assertEquals("Add at least two stations.", GameDraftValidator.validationError(draft))
    }

    @Test
    fun rejectsUntitledStation() {
        val draft = GameDraft(
            title = "Campus trail",
            stations = listOf(StationDraft("Start", ""), StationDraft(" ", ""))
        )

        assertEquals(
            "Enter a title for every station.",
            GameDraftValidator.validationError(draft)
        )
    }
}