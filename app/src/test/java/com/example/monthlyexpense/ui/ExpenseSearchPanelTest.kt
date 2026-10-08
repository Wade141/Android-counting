package com.example.monthlyexpense.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.*
import com.example.monthlyexpense.search.*
import com.example.monthlyexpense.ui.search.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseSearchPanelTest {
    @get:Rule(order = 0) val dispatchers: org.junit.rules.TestRule = ComposeDispatcherIsolationRule()
    @get:Rule(order = 1) val composeRule = createComposeRule()
    @Test fun summaryDescribesAllMatchesAndResultOpensEditor() {
        val category = ExpenseCategory("FOOD", "饮食", 0xff123456, true, 0)
        var selected: Long? = null
        val row = ExpenseRecord(7, 100, category, "午餐", "公司楼下", 0, ExpenseSource.MANUAL, null)
        composeRule.setContent { ExpenseAppTheme {
            ExpenseSearchPanel(ExpenseSearchUiState(items = listOf(row),
                summary = ExpenseSearchSummary(101, 123456), canEdit = true, dirty = false), listOf(category),
                ExpenseSearchCallbacks(onEdit = { selected = it }))
        } }
        composeRule.onNodeWithText("共 101 笔 · 合计 ¥1234.56").assertIsDisplayed()
        composeRule.onNodeWithText("午餐").performClick()
        composeRule.runOnIdle { assertEquals(7L, selected) }
    }
    @Test fun filterSheetOffersDateAmountCategoryAndSourceAndShowsValidation() {
        var applied = 0
        composeRule.setContent { ExpenseAppTheme {
            ExpenseSearchPanel(ExpenseSearchUiState(filterDraft = ExpenseSearchFilters(),
                validationError = "最低金额不能大于最高金额"), emptyList(),
                ExpenseSearchCallbacks(onApplyFilters = { applied++ }))
        } }
        composeRule.onNodeWithText("最低金额（元）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("记账来源").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最低金额不能大于最高金额").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("应用筛选").performClick()
        composeRule.runOnIdle { assertEquals(1, applied) }
    }

    @Test fun recreatedTextFieldReconcilesAbandonedImeComposition() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent { ExpenseAppTheme {
            ExpenseSearchPanel(ExpenseSearchUiState(keyword = "cha", isComposing = true), emptyList(),
                ExpenseSearchCallbacks(onKeyword = { word, composing -> changes += word to composing }))
        } }
        composeRule.runOnIdle { assertEquals(listOf("cha" to false), changes) }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w360dp-h1000dp")
    fun filterActionsFollowSourceOptionsInsteadOfBeingPushedToWindowBottom() {
        composeRule.setContent { ExpenseAppTheme {
            ExpenseSearchPanel(ExpenseSearchUiState(filterDraft = ExpenseSearchFilters()), emptyList(), ExpenseSearchCallbacks())
        } }
        val source = composeRule.onNodeWithText("截图记账").fetchSemanticsNode().boundsInRoot
        val apply = composeRule.onNodeWithText("应用筛选").fetchSemanticsNode().boundsInRoot
        val gapDp = with(composeRule.density) { (apply.top - source.bottom).toDp().value }
        assertTrue("Buttons should follow sources with a small gap, actual gap=$gapDp dp", gapDp in 0f..56f)
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w1000dp-h1100dp")
    fun actionsRemainInsideSafeAreaForSmallScreensLandscapeLargeFontsAndKeyboard() {
        val width = mutableStateOf(360.dp)
        val height = mutableStateOf(800.dp)
        val bottomInset = mutableStateOf(48.dp)
        val fontScale = mutableFloatStateOf(1f)
        var applied = 0
        var reset = 0
        var density = 1f
        val categories = List(20) { ExpenseCategory("C$it", "分类$it", 0xff123456, false, it) }
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            density = baseDensity.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.floatValue)) {
                ExpenseAppTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        ExpenseSearchFilterContent(ExpenseSearchFilters(), null, categories,
                            ExpenseSearchCallbacks(onApplyFilters = { applied++ }, onResetDraft = { reset++ }),
                            Modifier.requiredSize(width.value, height.value).testTag("filter-frame"),
                            WindowInsets(top = with(baseDensity) { 24.dp.roundToPx() },
                                bottom = with(baseDensity) { bottomInset.value.roundToPx() }))
                    }
                }
            }
        }
        // Simulated window/inset changes exercise the same measured content used by the Dialog.
        val cases = listOf(
            Triple(360.dp to 800.dp, 48.dp, 1f),
            Triple(320.dp to 568.dp, 48.dp, 1.5f),
            Triple(800.dp to 360.dp, 24.dp, 1f),
            Triple(360.dp to 800.dp, 320.dp, 1.3f),
            Triple(900.dp to 1000.dp, 24.dp, 2f)
        )
        for ((size, inset, scale) in cases) {
            composeRule.runOnIdle {
                width.value = size.first; height.value = size.second
                bottomInset.value = inset; fontScale.floatValue = scale
            }
            val frame = composeRule.onNodeWithTag("filter-frame").fetchSemanticsNode().boundsInRoot
            listOf("重置", "应用筛选").forEach { title ->
                val button = composeRule.onNodeWithText(title).assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue("$title overlaps bottom inset for $size/$inset", bounds.bottom <= frame.bottom - inset.value * density)
                assertTrue("$title must remain in the window", bounds.left >= frame.left && bounds.right <= frame.right && bounds.top >= frame.top)
                button.performClick()
            }
            composeRule.onNodeWithText("截图记账").performScrollTo().assertIsDisplayed()
        }
        composeRule.runOnIdle { assertEquals(cases.size, applied); assertEquals(cases.size, reset) }
    }
}
