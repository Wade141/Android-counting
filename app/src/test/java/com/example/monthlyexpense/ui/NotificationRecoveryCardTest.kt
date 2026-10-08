package com.example.monthlyexpense.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.ui.home.AutoBookkeepingCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationRecoveryCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recoveryDisablesRepeatActionAndFailureOffersRetryAndSettings() {
        val state = mutableStateOf(NotificationListenerConnectionState.RECOVERING)
        var retries = 0
        var settings = 0
        compose.setContent {
            AutoBookkeepingCard(state.value, true, true, true,
                onAutoChanged = {}, onWeChatChanged = {}, onAlipayChanged = {},
                onRequestAccess = { settings++ }, onReplayRecent = { retries++ })
        }
        compose.onNodeWithText("正在恢复…").assertIsNotEnabled()
        compose.runOnIdle { state.value = NotificationListenerConnectionState.RECOVERY_FAILED }
        compose.onNodeWithText("暂未恢复，请重试或检查系统设置").assertIsDisplayed()
        compose.onNodeWithText("重试恢复").performClick()
        compose.onNodeWithText("系统设置").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, settings)
            state.value = NotificationListenerConnectionState.CONNECTED
        }
        compose.onNodeWithText("已授权，监听正常").assertIsDisplayed()
        compose.onNodeWithText("补记最近30分钟").assertIsDisplayed()
    }
}
