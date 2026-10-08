package com.example.monthlyexpense.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.classification.*
import com.example.monthlyexpense.ui.ComposeDispatcherIsolationRule
import com.example.monthlyexpense.ui.ExpenseAppTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryKeywordScreenTest {
    @get:Rule(order = 0) val dispatchers: org.junit.rules.TestRule = ComposeDispatcherIsolationRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val categories = listOf(ExpenseCategory("FOOD", "饮食", 0, true, 0), ExpenseCategory("SHOPPING", "购物", 0, true, 1))
    @Test @org.robolectric.annotation.Config(qualifiers = "w400dp-h1000dp")
    fun dialogHonorsActualAvailableHeightInsteadOfConfiguredScreenHeight() {
        compose.setContent { ExpenseAppTheme {
            CategoryKeywordScreen(CategoryKeywordUiState(CategoryRuleSet("FOOD"), 0), categories, CategoryKeywordCallbacks())
        } }
        compose.runOnIdle {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val content = dialog.window!!.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val availableHeight = with(compose.density) { 360.dp.roundToPx() }
            val availableWidth = with(compose.density) { 400.dp.roundToPx() }
            // Exercise the real DialogLayout with a smaller available window (system bars/IME).
            // Compose 1.7.6's usePlatformDefaultWidth=false path replaces this height with screenHeightDp.
            content.measure(android.view.View.MeasureSpec.makeMeasureSpec(availableWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(availableHeight, android.view.View.MeasureSpec.AT_MOST))
            assertTrue("Dialog content ${content.measuredHeight}px exceeds available ${availableHeight}px", content.measuredHeight <= availableHeight)
        }
    }
    @Test @org.robolectric.annotation.Config(qualifiers = "w360dp-h480dp")
    fun bottomActionsArePartOfScrollableFormAndCanBeReached() {
        var saves = 0; var cancels = 0
        compose.setContent { ExpenseAppTheme {
            CategoryKeywordContent(CategoryKeywordUiState(CategoryRuleSet("FOOD", keywords = List(50) {
                CategoryKeywordRule("$it", "商户名称$it", "商户名称$it") }), 0), categories,
                CategoryKeywordCallbacks(save = { saves++ }, cancel = { cancels++ }))
        } }
        compose.onNodeWithTag("keyword-save").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("keyword-cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, cancels) }
    }
    @Test @org.robolectric.annotation.Config(qualifiers = "w360dp-h480dp")
    fun realDialogCanScrollToBothActionsWithLargeFonts() {
        var saves = 0; var cancels = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) { ExpenseAppTheme {
                CategoryKeywordScreen(CategoryKeywordUiState(CategoryRuleSet("FOOD", keywords = List(50) {
                    CategoryKeywordRule("$it", "长商户名称和分店名称$it", "长商户名称和分店名称$it") }), 0), categories,
                    CategoryKeywordCallbacks(save = { saves++ }, cancel = { cancels++ }))
            } }
        }
        compose.onNodeWithTag("keyword-save").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("keyword-cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, cancels) }
    }
    @Test @org.robolectric.annotation.Config(qualifiers = "w1000dp-h1100dp")
    fun saveAndCancelCanScrollAboveIme() {
        val width = mutableStateOf(360.dp); val height = mutableStateOf(800.dp)
        val inset = mutableStateOf(48.dp); val scale = mutableFloatStateOf(1f)
        var density = 1f; var saves = 0; var cancels = 0
        compose.setContent {
            val base = LocalDensity.current; density = base.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.floatValue)) { ExpenseAppTheme {
                CategoryKeywordContent(CategoryKeywordUiState(CategoryRuleSet("FOOD", keywords = List(50) {
                    CategoryKeywordRule("$it", "长商户名称和分店名称$it", "长商户名称和分店名称$it") }), 0), categories,
                    CategoryKeywordCallbacks(save = { saves++ }, cancel = { cancels++ }),
                    Modifier.requiredSize(width.value, height.value).testTag("keyword-frame"),
                    WindowInsets(top = with(base) { 24.dp.roundToPx() }, bottom = with(base) { inset.value.roundToPx() }))
            } }
        }
        val cases = listOf(Triple(320.dp to 568.dp, 48.dp, 2f), Triple(800.dp to 360.dp, 24.dp, 1f),
            Triple(360.dp to 800.dp, 320.dp, 1.3f), Triple(900.dp to 1000.dp, 24.dp, 2f))
        cases.forEach { (size, bottom, font) ->
            compose.runOnIdle { width.value = size.first; height.value = size.second; inset.value = bottom; scale.floatValue = font }
            val frame = compose.onNodeWithTag("keyword-frame").fetchSemanticsNode().boundsInRoot
            listOf("keyword-save", "keyword-cancel").forEach { tag ->
                val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.bottom <= frame.bottom - bottom.value * density)
                assertTrue(bounds.left >= frame.left && bounds.right <= frame.right && bounds.top >= frame.top)
                node.performClick()
            }
            compose.onNodeWithText("测试消费名称").performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(cases.size, saves); assertEquals(cases.size, cancels) }
    }
    @Test fun exactChipSupportsEditingAndRemovingAndImeAdds() {
        var edits = 0; var removes = 0; var adds = 0
        compose.setContent { ExpenseAppTheme {
            CategoryKeywordContent(CategoryKeywordUiState(CategoryRuleSet("FOOD", keywords = listOf(CategoryKeywordRule("a", "瑞幸", "瑞幸", KeywordMatchMode.EXACT))), 0), categories,
                CategoryKeywordCallbacks(edit = { edits++ }, remove = { removes++ }, add = { adds++ }))
        } }
        compose.onNodeWithText("瑞幸 · 精确").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("edit action", 1, edits) }
        compose.onNodeWithTag("remove-a").performClick()
        compose.runOnIdle { assertEquals("remove action", 1, removes) }
        compose.onNodeWithText("新关键词").performScrollTo().performImeAction()
        compose.onNodeWithTag("add-keyword").performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, removes); assertEquals(2, adds) }
    }
    @Test fun manualOtherSurvivesNameChangesAndSaveCarriesExplicitSelection() {
        var result: Pair<String, Boolean>? = null
        val rules = listOf(CategoryRuleSet("FOOD", true, keywords = listOf(CategoryKeywordRule("a", "咖啡", "咖啡"))))
        compose.setContent { ExpenseAppTheme {
            com.example.monthlyexpense.ui.forms.ExpenseEntryDialog(categories + ExpenseCategory("OTHER", "其他", 0, true, 2), false, {},
                { _, key, _, _, explicit -> result = key to explicit }, rules)
        } }
        compose.onNodeWithText("金额（元）").performTextInput("10")
        compose.onNodeWithText("消费名称").performTextInput("咖啡")
        compose.onNodeWithTag("category-FOOD").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("category-OTHER").performScrollTo().performClick()
        compose.onNodeWithText("消费名称").performScrollTo().performTextReplacement("咖啡店")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("OTHER" to true, result) }
    }
    @Test fun clearingNameClearsUnconfirmedSuggestion() {
        val rules = listOf(CategoryRuleSet("FOOD", true, keywords = listOf(CategoryKeywordRule("a", "咖啡", "咖啡"))))
        compose.setContent { ExpenseAppTheme {
            com.example.monthlyexpense.ui.forms.ExpenseEntryDialog(categories + ExpenseCategory("OTHER", "其他", 0, true, 2), false, {}, { _, _, _, _, _ -> }, rules)
        } }
        compose.onNodeWithText("消费名称").performTextInput("咖啡")
        compose.onNodeWithTag("category-FOOD").performScrollTo().assertIsSelected()
        compose.onNodeWithText("消费名称").performScrollTo().performTextClearance()
        compose.onNodeWithTag("category-OTHER").performScrollTo().assertIsSelected()
    }
}
