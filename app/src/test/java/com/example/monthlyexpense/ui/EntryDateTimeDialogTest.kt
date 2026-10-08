package com.example.monthlyexpense.ui

import android.app.DatePickerDialog
import android.content.DialogInterface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.ui.forms.EntryDateTimeDialog
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class EntryDateTimeDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cancelDiscardsBothDraftsAndReopenShowsOriginalValues() {
        val open = mutableStateOf(true)
        var saved: Pair<LocalDate, LocalTime>? = null
        rule.setContent {
            if (open.value) EntryDateTimeDialog(LocalDate.of(2026, 7, 1), LocalTime.of(8, 15),
                onDismiss = { open.value = false }, onConfirm = { date, time -> saved = date to time })
        }
        rule.onNodeWithText("2026-07-01").assertIsDisplayed()
        rule.onNodeWithText("08:15").assertIsDisplayed()
        rule.onNodeWithText("修改日期").performClick()
        rule.runOnIdle {
            val picker = ShadowDialog.getLatestDialog() as DatePickerDialog
            picker.datePicker.updateDate(2026, 6, 12)
            picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        rule.onNodeWithText("2026-07-12").assertIsDisplayed()
        rule.onNodeWithText("修改时间").performClick()
        rule.onNodeWithTag("clock-time-input").performTextReplacement("23:59")
        rule.onNodeWithText("确定").performClick()
        rule.onNodeWithText("23:59").assertIsDisplayed()
        rule.onNodeWithTag("date-time-cancel").performClick()
        rule.runOnIdle { assertNull(saved); open.value = true }
        rule.onNodeWithText("2026-07-01").assertIsDisplayed()
        rule.onNodeWithText("08:15").assertIsDisplayed()
    }

    @Test fun cancelClockKeepsSummaryTimeAndConfirmPreservesSeconds() {
        var saved: LocalTime? = null
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            EntryDateTimeDialog(LocalDate.of(2026, 7, 1), LocalTime.of(8, 15, 32), {}, { _, time -> saved = time })
        }
        rule.onNodeWithText("修改时间").performClick()
        rule.onNodeWithTag("clock-time-input").performTextReplacement("21:30")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("clock-time-input").assertTextContains("21:30")
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("08:15").assertIsDisplayed()
        rule.onNodeWithTag("date-time-confirm").performClick()
        assertEquals(LocalTime.of(8, 15, 32), saved)
    }
}
