package com.example.monthlyexpense.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.monthlyexpense.notification.replay.ReplayResultPresentation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationReplayPopupTest {
    @get:Rule val compose = createComposeRule()
    @Test fun largeFontKeepsDismissActionReachable() {
        var dismissed = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 2f)) {
                ExpenseAppTheme {
                    NotificationReplayPopup(ReplayResultPresentation("large", "补记尚未完成",
                        "通知接收当前状态尚未确认。", "已补记 12 笔，3 条待确认，另有 5 条未完成，可重试。", true),
                        onRetry = {}, onDismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("知道了").performScrollTo().performClick()
        org.junit.Assert.assertTrue(dismissed)
    }
    @Test fun resultPopupShowsConfirmedCountAndCanBeDismissed() {
        var showing by mutableStateOf(true)
        compose.setContent {
            ExpenseAppTheme {
                if (showing) NotificationReplayPopup("已补记 2 笔", true) { showing = false }
            }
        }
        compose.onNodeWithTag("notification_replay_popup").assertIsDisplayed()
        compose.onNodeWithText("补记完成").assertIsDisplayed()
        compose.onNodeWithText("已补记 2 笔").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("已补记 2 笔").assertDoesNotExist()
    }

    @Test fun partialResultSeparatesCurrentConnectionAndRetryAction() {
        var retries = 0
        compose.setContent {
            ExpenseAppTheme {
                NotificationReplayPopup(
                    result = ReplayResultPresentation("id", "补记尚未完成", "通知接收当前已断开。",
                        "已补记 2 笔，另有 1 条未完成，可重试。", canRetry = true),
                    onRetry = { retries++ }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("补记尚未完成").assertIsDisplayed()
        compose.onNodeWithText("通知接收当前已断开。").assertIsDisplayed()
        compose.onNodeWithText("已补记 2 笔，另有 1 条未完成，可重试。").assertIsDisplayed()
        compose.onNodeWithText("重试补记").performClick()
        assertEquals(1, retries)
    }

    @Test fun completeStructuredResultDoesNotOfferRetry() {
        compose.setContent {
            ExpenseAppTheme {
                NotificationReplayPopup(
                    result = ReplayResultPresentation("id", "补记检查完成", "通知接收已恢复。",
                        "本次补记 2 笔，1 条待确认。", canRetry = false),
                    onRetry = {}, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("补记检查完成").assertIsDisplayed()
        compose.onNodeWithText("重试补记").assertDoesNotExist()
        compose.onNodeWithText("知道了").assertIsDisplayed()
    }
}
