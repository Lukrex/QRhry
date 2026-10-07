package com.qrhry.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qrhry.app.MainActivity
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
        composeRule.onNodeWithText("First authored option").assertIsDisplayed()
        composeRule.onNodeWithText("Second authored option").assertIsDisplayed()
        composeRule.onNodeWithText("Game title").assertIsDisplayed()
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