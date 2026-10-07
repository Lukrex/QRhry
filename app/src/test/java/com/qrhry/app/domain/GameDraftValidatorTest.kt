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
}