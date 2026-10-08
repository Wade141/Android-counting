package com.example.monthlyexpense.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
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
class SettingsDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun settingsShowsBothOptionsAndRoutesBackground() {
        var background = 0
        var font = 0
        rule.setContent { SettingsDialog(AppearanceState(busy = false), null, {}, { font++ }, {}, {}, { background++ }) }
        rule.onNodeWithText("选择字体颜色").performClick()
        rule.onNodeWithText("个性化背景").performClick()
        rule.runOnIdle { assertEquals(1, font); assertEquals(1, background) }
    }

    @Test fun customColorPreviewsAndInvalidInputCannotApply() {
        var saved: Long? = null
        rule.setContent { SettingsDialog(AppearanceState(busy = false, editorOpen = true), null, {}, {}, {}, { saved = it }, {}) }
        rule.onNodeWithTag("font-color-input").performScrollTo().performTextReplacement("#123ABC")
        rule.onNodeWithText("午餐 · 示例账单").performScrollTo()
        assertTextColor("午餐 · 示例账单", Color(0xFF123ABC))
        assertTextColor("9月19日 12:30", Color(0xFF123ABC))
        rule.onNodeWithTag("font-color-input").performScrollTo().performTextReplacement("#bad")
        rule.onNodeWithText("应用").assertIsNotEnabled()
        rule.onNodeWithContentDescription("预设颜色海蓝").performScrollTo().performClick()
        rule.onNodeWithText("应用").performClick()
        assertEquals(0xFF1565C0L, saved)
    }

    @Test fun cancelDoesNotApplyAndDefaultSubmitsReset() {
        var calls = 0
        var result: Long? = 1L
        var cancels = 0
        rule.setContent { SettingsDialog(AppearanceState(color = 0xFF123ABCL, busy = false, editorOpen = true), null, {}, {}, { cancels++ }, { calls++; result = it }, {}) }
        rule.onNodeWithTag("font-color-input").performScrollTo().performTextReplacement("#FFFFFF")
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, calls)
        assertEquals(1, cancels)
        rule.onNodeWithText("恢复默认颜色").performScrollTo().performClick()
        rule.onNodeWithText("应用").performClick()
        assertNull(result)
    }

    @Test fun appliedThemeColorsMainCardAndSecondaryTextAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AppearancePreferences(context)
        try {
            preferences.save(0xFF1565C0L)
            val loaded = AppearancePreferences(context).load()
            assertEquals(0xFF1565C0L, loaded)
            rule.setContent {
                ExpenseAppTheme(loaded) {
                    Column {
                        Text("主页面标题")
                        Surface(color = Color.White) { Text("账单金额") }
                        Text("账单日期", color = secondaryTextColor())
                    }
                }
            }
            listOf("主页面标题", "账单金额", "账单日期").forEach { assertTextColor(it, Color(0xFF1565C0)) }
            preferences.save(null)
            assertNull(AppearancePreferences(context).load())
        } finally { preferences.save(null) }
    }

    @Test fun ordinaryBudgetStatusUsesCustomFontColor() {
        rule.setContent {
            ExpenseAppTheme(0xFF1565C0L) {
                com.example.monthlyexpense.ui.home.MonthlyOverviewCard(
                    emptyMap(), com.example.monthlyexpense.budget.BudgetSummary.from(
                        monthlySpentCents = 0L, monthlyBudgetCents = 0L,
                        todaySpentCents = 0L, dailyBudgetCents = 0L
                    ), {}
                )
            }
        }
        assertTextColor("尚未设置本月预算", Color(0xFF1565C0))
    }

    private fun assertTextColor(text: String, color: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(color, layouts.single().layoutInput.style.color)
    }
}
