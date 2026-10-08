package com.example.monthlyexpense.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsActions
import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.categories.ColorCodec
import com.example.monthlyexpense.ui.budget.BudgetDialog
import com.example.monthlyexpense.ui.categories.CategoryEditorDialog
import com.example.monthlyexpense.ui.forms.ExpenseEditDialog
import com.example.monthlyexpense.ui.forms.ExpenseEntryDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseFormStateRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newExpenseDraftSurvivesStateRestorationIncludingSelectedCategory() {
        val restorationTester = StateRestorationTester(composeRule)
        val saved = mutableListOf<NewExpenseFormValue>()

        restorationTester.setContent {
            ExpenseEntryDialog(
                categories = categories,
                saving = false,
                onDismiss = {},
                onSave = { amount, categoryKey, name, note, _ ->
                    saved += NewExpenseFormValue(amount, categoryKey, name, note)
                }
            )
        }

        textFields()[0].performTextReplacement("250.50")
        textFields()[1].performTextReplacement("晚饭")
        textFields()[2].performTextReplacement("和朋友")
        composeRule.onNodeWithTag("category-travel").performScrollTo().performClick()
        composeRule.onNodeWithTag("category-travel").assertIsSelected()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("250.50").assertIsDisplayed()
        composeRule.onNodeWithText("晚饭").assertIsDisplayed()
        composeRule.onNodeWithText("和朋友").assertIsDisplayed()
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(
            listOf(NewExpenseFormValue(25_050L, "travel", "晚饭", "和朋友")),
            saved
        )
    }

    @Test
    fun newExpenseDelegatesOverLimitAmountToViewModelValidation() {
        val saved = mutableListOf<NewExpenseFormValue>()

        composeRule.setContent {
            ExpenseEntryDialog(
                categories = categories,
                saving = false,
                onDismiss = {},
                onSave = { amount, categoryKey, name, note, _ ->
                    saved += NewExpenseFormValue(amount, categoryKey, name, note)
                }
            )
        }

        textFields()[0].performTextReplacement("100000000.00")
        textFields()[1].performTextReplacement("超限消费")
        composeRule.onNodeWithText("保存").performClick()

        assertEquals(
            listOf(NewExpenseFormValue(10_000_000_000L, "OTHER", "超限消费", "")),
            saved
        )
    }

    @Test
    fun editedTimeSurvivesRestorationAndIsSubmitted() {
        val restorationTester = StateRestorationTester(composeRule)
        var savedTime: java.time.LocalTime? = null
        restorationTester.setContent {
            ExpenseEditDialog(
                expense = expense(id = 1L, name = "早餐", note = ""),
                categories = categories, saving = false, onDismiss = {},
                onSave = { _, _, _, _, time, _, _ -> savedTime = time }
            )
        }
        composeRule.onNodeWithText("修改入账时间").performScrollTo().performClick()
        composeRule.onNodeWithText("修改时间").performClick()
        composeRule.onNodeWithTag("clock-time-input").performTextReplacement("23:59")
        composeRule.onNodeWithText("确定").performClick()
        composeRule.onNodeWithTag("date-time-confirm").performClick()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("入账时间：23:59").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存更改").performClick()
        assertEquals(java.time.LocalTime.of(23, 59), savedTime)
    }

    @Test
    fun editedDateSurvivesRestorationAndIsSubmitted() {
        val restorationTester = StateRestorationTester(composeRule)
        var savedDate: java.time.LocalDate? = null
        restorationTester.setContent {
            ExpenseEditDialog(
                expense = expense(id = 1L, name = "早餐", note = ""),
                categories = categories, saving = false, onDismiss = {},
                onSave = { _, _, _, date, _, _, _ -> savedDate = date }
            )
        }
        composeRule.onNodeWithText("修改入账时间").performScrollTo().performClick()
        composeRule.onNodeWithText("修改日期").performClick()
        composeRule.runOnIdle {
            val picker = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.DatePickerDialog
            picker.datePicker.updateDate(2026, 6, 12)
            picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("2026-07-12").assertIsDisplayed()
        composeRule.onNodeWithTag("date-time-confirm").performClick()
        composeRule.onNodeWithText("保存更改").performClick()
        assertEquals(java.time.LocalDate.of(2026, 7, 12), savedDate)
    }

    @Test
    fun expenseEditDraftSurvivesRestorationAndResetsForAnotherExpense() {
        val restorationTester = StateRestorationTester(composeRule)
        val expense = mutableStateOf(expense(id = 1L, name = "早餐", note = "原备注"))
        val saved = mutableListOf<EditExpenseFormValue>()

        restorationTester.setContent {
            ExpenseEditDialog(
                expense = expense.value,
                categories = categories,
                saving = false,
                onDismiss = {},
                onSave = { name, note, categoryKey, _, _, _, _ ->
                    saved += EditExpenseFormValue(name, note, categoryKey)
                }
            )
        }

        textFields()[0].performTextReplacement("午饭")
        textFields()[1].performTextReplacement("新备注")
        composeRule.onNodeWithTag("category-travel").performScrollTo().performClick()
        composeRule.onNodeWithTag("category-travel").assertIsSelected()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("午饭").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("新备注").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存更改").performClick()
        assertEquals(listOf(EditExpenseFormValue("午饭", "新备注", "travel")), saved)

        composeRule.runOnIdle { expense.value = expense(id = 2L, name = "地铁", note = "通勤") }
        composeRule.onNodeWithText("地铁").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("通勤").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存更改").performClick()
        assertEquals(EditExpenseFormValue("地铁", "通勤", "food"), saved.last())
    }

    @Test
    fun budgetDraftSurvivesRestorationAndResetsWhenOnlyKindChanges() {
        val restorationTester = StateRestorationTester(composeRule)
        val kind = mutableStateOf(BudgetKind.MONTHLY)
        val initialCents = mutableStateOf(10_000L)
        val saved = mutableListOf<Long>()

        restorationTester.setContent {
            BudgetDialog(
                kind = kind.value,
                initialCents = initialCents.value,
                saving = false,
                onDismiss = {},
                onSave = { saved.add(it) }
            )
        }

        textFields()[0].performTextReplacement("789.12")
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("789.12").assertIsDisplayed()
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(78_912L), saved)

        composeRule.runOnIdle {
            kind.value = BudgetKind.DAILY
        }
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(10_000L, saved.last())
    }

    @Test
    fun budgetDraftResetsWhenOnlyInitialCentsChanges() {
        val restorationTester = StateRestorationTester(composeRule)
        val initialCents = mutableStateOf(10_000L)
        val saved = mutableListOf<Long>()

        restorationTester.setContent {
            BudgetDialog(
                kind = BudgetKind.MONTHLY,
                initialCents = initialCents.value,
                saving = false,
                onDismiss = {},
                onSave = { saved.add(it) }
            )
        }

        textFields()[0].performTextReplacement("789.12")
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("789.12").assertIsDisplayed()

        composeRule.runOnIdle { initialCents.value = 2_500L }
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(2_500L), saved)
    }

    @Test
    fun categoryEditorNewDraftRestoresHsvAndAlphaAndResetsAcrossIdentities() {
        val restorationTester = StateRestorationTester(composeRule)
        val existing = mutableStateOf<ExpenseCategory?>(null)
        val saved = mutableListOf<CategoryEditorFormValue>()

        restorationTester.setContent {
            CategoryEditorDialog(
                existing = existing.value,
                saving = false,
                onDismiss = {},
                onSave = { name, color -> saved += CategoryEditorFormValue(name, color) }
            )
        }

        textFields()[0].performTextReplacement("自定义分类")
        setSlider("category-hue-slider", 180f)
        setSlider("category-saturation-slider", .5f)
        setSlider("category-brightness-slider", .25f)
        setSlider("category-alpha-slider", .4f)
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("自定义分类").assertIsDisplayed()
        assertSliderValue("category-hue-slider", 180f)
        assertSliderValue("category-saturation-slider", .5f)
        assertSliderValue("category-brightness-slider", .25f)
        assertSliderValue("category-alpha-slider", .4f)
        composeRule.onNodeWithText("保存").performClick()
        assertEquals("自定义分类", saved.single().name)

        composeRule.runOnIdle { existing.value = categories.first() }
        composeRule.onNodeWithText("保存").performClick()
        assertEquals(
            CategoryEditorFormValue("餐饮", ColorCodec.parse("#FFEF6C45")!!),
            saved.last()
        )

        composeRule.runOnIdle { existing.value = null }
        composeRule.onNodeWithText("添加分类").assertIsDisplayed()
        composeRule.onNodeWithText("保存").assertIsNotEnabled()
    }

    private fun textFields() = composeRule.onAllNodes(hasSetTextAction())

    private fun setSlider(tag: String, value: Float) {
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) {
            it(value)
        }
    }

    private fun assertSliderValue(tag: String, expected: Float) {
        assertEquals(
            expected,
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
            ].current
        )
    }
}

private val categories = listOf(
    ExpenseCategory("food", "餐饮", 0xFFEF6C45L, builtIn = false, sortOrder = 0),
    ExpenseCategory("travel", "出行", 0xFF2D9CDBL, builtIn = false, sortOrder = 1)
)

private fun expense(id: Long, name: String, note: String) = ExpenseRecord(
    id = id,
    amountCents = 1_200L,
    category = categories.first(),
    name = name,
    note = note,
    spentAt = 0L,
    source = ExpenseSource.MANUAL,
    merchant = null
)

private data class NewExpenseFormValue(
    val amount: Long,
    val categoryKey: String,
    val name: String,
    val note: String
)

private data class EditExpenseFormValue(
    val name: String,
    val note: String,
    val categoryKey: String
)

private data class CategoryEditorFormValue(val name: String, val color: Long)
