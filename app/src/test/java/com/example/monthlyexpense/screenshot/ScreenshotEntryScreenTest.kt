package com.example.monthlyexpense.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ui.ExpenseAppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ScreenshotEntryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(ScreenshotEntryState(active = true, date = "2026-09-15"))
    private var saves = 0
    private var duplicateSaves = 0
    private var dismissals = 0
    private var replacement: Boolean? = null
    private var edits = 0

    private fun show(doubleFont: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,
                fontScale = if (doubleFont) 2f else 1f)) {
                ExpenseAppTheme {
                    Box(Modifier.size(320.dp, 480.dp)) {
                        ScreenshotEntryScreen(state.value,
                            onEdit = { _, _ -> edits++ },
                            onConfirm = { state.value = state.value.copy(confirmed = it) },
                            onChooseImage = {}, onSave = { saves++ }, onReturn = {},
                            onDismissDuplicate = { dismissals++ },
                            onSaveDuplicate = { duplicateSaves++ }, onReplace = { replacement = it })
                    }
                }
            }
        }
    }

    @Test fun saveRequiresExplicitConfirmationAndRemainsReachableAtDoubleFont() {
        state.value = state.value.copy(categories = listOf(
            ExpenseCategory("OTHER", "其他", 0L, true, 0),
            ExpenseCategory("LONG", "很长的自定义生活支出分类名称", 0L, false, 1)))
        show(doubleFont = true)
        compose.onNodeWithText("很长的自定义生活支出分类名称")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.onNodeWithText("确认保存").performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("我已核对：这是人民币成功支出").performScrollTo().performClick()
        compose.onNodeWithText("确认保存").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun savingDisablesFormConfirmationNavigationAndImageSelection() {
        state.value = state.value.copy(saving = true, confirmed = true)
        show()
        listOf("返回", "从相册选择截图", "金额（元）", "名称", "备注", "日期（YYYY-MM-DD）",
            "我已核对：这是人民币成功支出", "保存中…").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun recognizingAllowsCorrectionButPreventsSaving() {
        state.value = state.value.copy(recognizing = true, confirmed = true)
        show()
        compose.onNodeWithText("正在自动识别截图…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("名称").performScrollTo().assertIsEnabled().performTextInput("修正商户")
        compose.onNodeWithText("确认保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, saves) }
    }

    @Test fun duplicateDialogNeverSavesUntilExplicitContinue() {
        state.value = state.value.copy(confirmed = true, duplicates = listOf(
            DuplicateExpense(1L, 1234L, "商户", 0L, "同图片")))
        show()
        compose.onNodeWithText("同图片", substring = true).assertExists()
        compose.onNodeWithText("¥12.34", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, duplicateSaves); assertEquals(0, saves) }
        compose.onNodeWithText("返回核对").performClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertEquals(0, duplicateSaves) }
        compose.onNodeWithText("仍然保存").performClick()
        compose.runOnIdle { assertEquals(1, duplicateSaves) }
    }

    @Test fun replacementRequiresExplicitDiscardAndCanBeCancelled() {
        state.value = state.value.copy(replacePending = true)
        show()
        compose.runOnIdle { assertNull(replacement) }
        compose.onNodeWithText("保留当前草稿").performClick()
        compose.runOnIdle { assertEquals(false, replacement) }
        compose.onNodeWithText("放弃草稿并替换").performClick()
        compose.runOnIdle { assertEquals(true, replacement) }
    }

    @Test fun duplicatePreviewIsBoundedAndShowsCalendarDate() {
        val spentAt = LocalDate.of(2026, 9, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        state.value = state.value.copy(duplicates = (1L..8L).map {
            DuplicateExpense(it, 1234L, "商户$it", spentAt, "同日同额同名称")
        })
        show()
        compose.onNodeWithText("商户6", substring = true).assertDoesNotExist()
        compose.onNodeWithText("另有 3 笔可能重复的账目。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("商户1", substring = true).performScrollTo()
            .assertTextContains("2026-09-15", substring = true)
        compose.onNodeWithText("仍然保存").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, duplicateSaves) }
    }

    @Test fun failedRecognitionKeepsManualFormAvailable() {
        state.value = state.value.copy(error = "无法识别，请手动填写")
        show()
        compose.onNodeWithText("无法识别，请手动填写").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("金额（元）").performScrollTo().assertIsEnabled().performTextInput("12.34")
        compose.onNodeWithText("确认保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, saves) }
    }

    @Test fun savedStateShowsSuccessInsteadOfEditableDraft() {
        state.value = state.value.copy(savedId = 42L)
        show()
        compose.onNodeWithText("保存成功").assertIsDisplayed()
        compose.onNodeWithText("金额（元）").assertDoesNotExist()
        compose.onNodeWithText("确认保存").assertDoesNotExist()
        compose.onNodeWithText("返回").assertIsEnabled()
    }
}
