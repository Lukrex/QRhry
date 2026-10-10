package com.qrhry.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qrhry.app.MainActivity
import com.qrhry.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLocalizationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun selectSlovak() {
        setApplicationLanguage("sk")
    }

    @After
    fun restoreSlovak() {
        setApplicationLanguage("sk")
    }

    @Test
    fun launchUsesSlovakDefaultAndExplicitLanguageChoicePersistsAcrossRecreation() {
        waitForLanguage("sk")
        composeRule.onNodeWithText("Vyberte režim").assertIsDisplayed()

        composeRule.onNodeWithText("Nastavenia").performClick()
        composeRule.onNodeWithText("English").performClick()
        waitForLanguage("en")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        waitForLanguage("en")
        composeRule.onNodeWithText("Language").assertIsDisplayed()

        composeRule.onNodeWithText("Slovenčina").performClick()
        waitForLanguage("sk")
        composeRule.onNodeWithText("Jazyk").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        waitForLanguage("sk")
        composeRule.onNodeWithText("Nastavenia").assertIsDisplayed()
    }

    @Test
    fun unsavedGameTaskAndOptionDraftSurviveLanguageChange() {
        composeRule.onNodeWithText("Vytvoriť / Upraviť").performClick()
        composeRule.onNodeWithText("Názov hry").performTextInput("Hra vytvorená v angličtine")
        composeRule.onAllNodesWithText("Názov stanovišťa")[0].performTextInput("Nádvorie")
        composeRule.onAllNodesWithText("Názov stanovišťa")[1].performTextInput("Knižnica")
        composeRule.onAllNodesWithText("Pridať úlohu s výberom odpovede")[0].performClick()
        composeRule.onNodeWithText("Otázka").performTextInput("Question authored in English")
        composeRule.onNodeWithText("Počet bodov").performTextReplacement("275")
        composeRule.onNodeWithText("Možnosť 1").performTextInput("First authored option")
        composeRule.onNodeWithText("Možnosť 2").performTextInput("Second authored option")

        composeRule.onNodeWithText("Domov").performClick()
        composeRule.onNodeWithText("Nastavenia").performClick()
        composeRule.onNodeWithText("English").performClick()
        waitForLanguage("en")
        composeRule.activityRule.scenario.recreate()
        waitForLanguage("en")
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Create / Edit").performClick()

        composeRule.onNodeWithText("Hra vytvorená v angličtine").assertIsDisplayed()
        composeRule.onNodeWithText("Nádvorie").assertIsDisplayed()
        composeRule.onNodeWithText("Question authored in English").assertIsDisplayed()
        composeRule.onNodeWithText("275").assertIsDisplayed()
        composeRule.onNodeWithText("First authored option").assertIsDisplayed()
        composeRule.onNodeWithText("Second authored option").assertIsDisplayed()
        composeRule.onNodeWithText("Game title").assertIsDisplayed()
    }

    @Test
    fun scoringAndResultLabelsAreLocalizedInBothSupportedLanguages() {
        waitForLanguage("sk")
        assertEquals("Skóre: 650 bodov", composeRule.activity.resources.getQuantityString(R.plurals.result_score, 650, 650))
        assertEquals("Skóre: 2 body", composeRule.activity.resources.getQuantityString(R.plurals.result_score, 2, 2))
        assertEquals("Dokončené úlohy: 7/8", composeRule.activity.getString(R.string.result_tasks_completed, 7, 8))
        assertEquals("Skóre sa v tejto relácii nezaznamenávalo.", composeRule.activity.getString(R.string.result_score_unavailable))

        setApplicationLanguage("en")
        assertEquals("Score: 650 points", composeRule.activity.resources.getQuantityString(R.plurals.result_score, 650, 650))
        assertEquals("Correct answer. 1 point awarded.", composeRule.activity.resources.getQuantityString(R.plurals.task_complete_points, 1, 1))
        assertEquals("Tasks completed: 7/8", composeRule.activity.getString(R.string.result_tasks_completed, 7, 8))
        assertEquals("Score was not recorded for this run.", composeRule.activity.getString(R.string.result_score_unavailable))
        assertEquals("Points", composeRule.activity.getString(R.string.task_points))
    }

    private fun setApplicationLanguage(language: String) {
        if (AppCompatDelegate.getApplicationLocales().get(0)?.language == language) return
        composeRule.activityRule.scenario.onActivity {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
        }
        waitForLanguage(language)
    }

    private fun waitForLanguage(language: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            AppCompatDelegate.getApplicationLocales().get(0)?.language == language
        }
        composeRule.waitForIdle()
        assertEquals(language, AppCompatDelegate.getApplicationLocales().get(0)?.language)
    }
}