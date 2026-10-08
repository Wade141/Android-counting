package com.example.monthlyexpense.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ui.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardAppearanceDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sliderHasTenPercentStopsAndKeepsTextAndBorderOpaque() {
        val original = CardAppearance(border = CardBorder(0xFF1565C0L))
        var saved: CardAppearance? = null
        rule.setContent { ExpenseAppTheme {
            CardAppearanceDialog(AppearanceState(cards = original, cardEditor = CardEditor.TRANSPARENCY,
                color = 0xFF123ABCL, busy = false), null, {}, { saved = it })
        } }
        val slider = rule.onNodeWithTag("card-transparency-slider")
        assertEquals(9, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        listOf(0f, 50f, 100f).forEach { value ->
            slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            rule.onNodeWithText("白框透明度：${value.toInt()}%").assertExists()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("示例账单").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(0xFF123ABC), layouts.single().layoutInput.style.color)
        rule.onNodeWithText("应用").performClick()
        assertEquals(original.copy(transparency = 100), saved)
    }

    @Test fun borderInputPresetsAndTransparentOptionPreserveFill() {
        var saved: CardAppearance? = null
        rule.setContent { ExpenseAppTheme {
            CardAppearanceDialog(AppearanceState(cards = CardAppearance(transparency = 60),
                cardEditor = CardEditor.BORDER, busy = false), null, {}, { saved = it })
        } }
        rule.onNodeWithTag("border-color-input").performScrollTo().performTextReplacement("#bad")
        rule.onNodeWithText("应用").assertIsNotEnabled()
        rule.onNodeWithTag("border-color-input").performTextReplacement("#80123456")
        rule.onNodeWithText("应用").performClick()
        assertEquals(CardAppearance(CardBorder(0x80123456L), 60), saved)
        rule.onNodeWithContentDescription("边框预设海蓝").performScrollTo().performClick()
        rule.onNodeWithText("应用").performClick()
        assertEquals(0xFF1565C0L, saved!!.border.color)
        rule.onNodeWithText("透明边框").performScrollTo().performClick()
        rule.onNodeWithText("应用").performClick()
        assertEquals(CardAppearance(transparency = 60), saved)
    }

    @Test fun cancelDoesNotSaveAndResetOnlyChangesSelectedSetting() {
        var saved: CardAppearance? = null
        var canceled = false
        val original = CardAppearance(CardBorder(0xFF123456L), 70)
        rule.setContent { ExpenseAppTheme {
            CardAppearanceDialog(AppearanceState(cards = original, cardEditor = CardEditor.TRANSPARENCY,
                busy = false), null, { canceled = true }, { saved = it })
        } }
        rule.onNodeWithTag("card-transparency-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        rule.onNodeWithText("取消").performClick()
        assertTrue(canceled)
        assertNull(saved)
        rule.onNodeWithText("恢复默认").performScrollTo().performClick()
        rule.onNodeWithText("应用").performClick()
        assertEquals(original.copy(transparency = 0), saved)
    }

    @Test fun cardsPersistIndependentlyOfFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AppearancePreferences(context)
        try {
            preferences.save(0xFF1565C0L)
            val cards = CardAppearance(CardBorder(0x80123456L), 100)
            preferences.saveCards(cards)
            assertEquals(cards, AppearancePreferences(context).loadCards())
            assertEquals(0xFF1565C0L, AppearancePreferences(context).load())
            preferences.save(null)
            assertEquals(cards, AppearancePreferences(context).loadCards())
        } finally {
            preferences.save(null)
            preferences.saveCards(CardAppearance())
        }
    }
}
