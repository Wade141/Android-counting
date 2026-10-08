package com.example.monthlyexpense.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.monthlyexpense.*
import com.example.monthlyexpense.ui.forms.ExpenseEditDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpecialBudgetUiTest {
    @get:Rule(order = 0)
    val dispatcherRule: org.junit.rules.TestRule = ComposeDispatcherIsolationRule()
    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun moveRequiresConfirmationAndCancelDoesNotSave() {
        val category = ExpenseCategory("OTHER", "其他", 0xFF58BFA6L, true, 0)
        val expense = ExpenseRecord(1, 6_021_680, category, "学费", "原备注",
            System.currentTimeMillis(), ExpenseSource.ALIPAY_AUTO, null)
        var saves = 0
        compose.setContent {
            ExpenseAppTheme {
                ExpenseEditDialog(expense, listOf(category), false, {},
                    onSave = { _, _, _, _, _, _, special ->
                        assertEquals(true, special)
                        saves++
                    })
            }
        }
        compose.onNodeWithText("移至专项预算").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("暂不移动").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("移至专项预算").performScrollTo().performClick()
        compose.onNodeWithText("确认移入").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun confirmationSurvivesRecreationAndSavesDraftCategoryAndNote() {
        val categories = listOf(
            ExpenseCategory("OTHER", "其他", 0xFF58BFA6L, true, 0),
            ExpenseCategory("EDUCATION", "教育", 0xFF123456L, false, 1))
        val expense = ExpenseRecord(1, 6_021_680, categories[0], "学费", "原备注",
            System.currentTimeMillis(), ExpenseSource.ALIPAY_AUTO, null)
        val saved = mutableListOf<ExpenseEditInput>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ExpenseAppTheme {
                ExpenseEditDialog(expense, categories, false, {},
                    onSave = { name, note, category, date, time, explicit, special ->
                        saved += ExpenseEditInput(name, note, category, date, time, explicit, special)
                    })
            }
        }
        compose.onNodeWithText("消费记录备注").performScrollTo().performTextReplacement("本学年学费")
        compose.onNodeWithText("教育").performScrollTo().performClick()
        compose.onNodeWithText("移至专项预算").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(0, saved.size) }
        compose.onNodeWithText("确认移入").performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(true, saved.single().isSpecial)
            assertEquals("本学年学费", saved.single().note)
            assertEquals("EDUCATION", saved.single().categoryKey)
        }
    }

    @Test fun cancellingMoveThenSavingDoesNotChangeMembership() {
        val category = ExpenseCategory("OTHER", "其他", 0, true, 0)
        val expense = ExpenseRecord(1, 100, category, "学费", "", 0, ExpenseSource.MANUAL, null)
        val memberships = mutableListOf<Boolean?>()
        compose.setContent {
            ExpenseAppTheme {
                ExpenseEditDialog(expense, listOf(category), false, {},
                    onSave = { _, _, _, _, _, _, special -> memberships += special })
            }
        }
        compose.onNodeWithText("移至专项预算").performScrollTo().performClick()
        compose.onNodeWithText("暂不移动").performClick()
        compose.onNodeWithText("保存更改").performClick()
        compose.runOnIdle { assertEquals(listOf<Boolean?>(null), memberships) }
    }

    @Test fun existingSpecialExpenseCanBeMovedBackWithConfirmation() {
        val category = ExpenseCategory("OTHER", "其他", 0, true, 0)
        val expense = ExpenseRecord(1, 100, category, "学费", "", 0, ExpenseSource.MANUAL, null, isSpecial = true)
        val memberships = mutableListOf<Boolean?>()
        compose.setContent {
            ExpenseAppTheme {
                ExpenseEditDialog(expense, listOf(category), false, {},
                    onSave = { _, _, _, _, _, _, special -> memberships += special })
            }
        }
        compose.onNodeWithText("移回日常预算").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, memberships.size) }
        compose.onNodeWithText("确认移回").performClick()
        compose.runOnIdle { assertEquals(listOf(false), memberships) }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w320dp-h480dp")
    fun moveAndConfirmationRemainReachableOnSmallScreenWithLargeFont() {
        val category = ExpenseCategory("OTHER", "其他", 0, true, 0)
        val expense = ExpenseRecord(1, 6_021_680, category, "本学年学校学费", "", 0, ExpenseSource.MANUAL, null)
        var saved = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)
            ) {
                ExpenseAppTheme {
                    ExpenseEditDialog(expense, listOf(category), false, {},
                        onSave = { _, _, _, _, _, _, special -> saved = special == true })
                }
            }
        }
        compose.onNodeWithText("移至专项预算").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("确认移入").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, saved) }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w320dp-h480dp")
    fun detailsShowsAllRecordsAndSupportsLargeFontScrolling() {
        val category = ExpenseCategory("OTHER", "其他", 0, true, 0)
        val expenses = (1..8).map { index ->
            ExpenseRecord(index.toLong(), 6_021_680, category, "专项账单$index", "备注$index", 0,
                ExpenseSource.MANUAL, null, classificationOrigin = com.example.monthlyexpense.classification.ClassificationOrigin.CONFLICT,
                isSpecial = true)
        }
        var selected: Long? = null
        var closed = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)
            ) {
                ExpenseAppTheme {
                    com.example.monthlyexpense.ui.home.SpecialBudgetDetailsDialog(
                        expenses, onDismiss = { closed = true }, onEdit = { selected = it.id }, onDelete = {})
                }
            }
        }
        compose.onNodeWithText("分类待确认", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("special-budget-details").performScrollToNode(hasText("专项账单8"))
        compose.onNodeWithText("备注8").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("special-budget-details").performScrollToIndex(8)
        compose.onAllNodesWithText("编辑记录").onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(8L, selected) }
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }
}
