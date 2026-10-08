package com.example.monthlyexpense.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpenseAppRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun zeroSignalDoesNotRefreshAndFirstResumeSignalRefreshesExactlyOnce() {
        var refreshSignal by mutableStateOf(0)
        var refreshCalls = 0

        composeRule.setContent {
            ExpenseRefreshEffect(refreshSignal) {
                refreshCalls++
            }
        }
        composeRule.waitForIdle()

        assertEquals(0, refreshCalls)

        composeRule.runOnIdle {
            refreshSignal = 1
        }
        composeRule.waitForIdle()

        assertEquals(1, refreshCalls)
    }
}
