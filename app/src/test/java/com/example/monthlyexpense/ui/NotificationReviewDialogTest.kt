package com.example.monthlyexpense.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.notification.repository.PendingNotification
import com.example.monthlyexpense.ui.home.NotificationReviewDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationReviewDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun requiresValidAmountAndEmitsEditedPayment() {
        var saved: Pair<Long, String>? = null
        val item = PendingNotification("event", "WECHAT", 10000, null, listOf(1800, 2000), null,
            "PURCHASE", listOf("amount_conflict"), "v1")
        compose.setContent { MaterialTheme {
            NotificationReviewDialog(item, false, {}, { cents, name -> saved = cents to name }, {}, {})
        } }
        compose.onNodeWithText("确认计入支出").assertIsNotEnabled()
        compose.onNodeWithTag("review-amount").performTextInput("18.20")
        compose.onNodeWithTag("review-name").performTextReplacement("午餐")
        compose.onNodeWithText("确认计入支出").performClick()
        assertEquals(1820L to "午餐", saved)
    }
}
