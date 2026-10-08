package com.example.monthlyexpense.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.notification.*
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.NotificationEventRepository
import com.example.monthlyexpense.ui.home.NotificationManagementPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationManagementPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closedPanelReadsCountOnlyAndLedgerChangesDoNotReloadReview() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("expenses.db")
        ExpenseDatabaseHelper(context).use { db ->
            val repo = NotificationEventRepository(db)
            repeat(55) {
                val d = PaymentDecisionEngine().evaluate(RawNotification(PaymentPackages.WECHAT, "微信支付",
                    "成功转账20元", "", emptyList(), 10000, "panel-$it", null))
                repo.stage(d); repo.process(d.eventId)
            }
        }
        NotificationMetrics.reset()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { if (visible.value) NotificationManagementPanel() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("待确认 55 条").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0L, NotificationMetrics.snapshot()[PipelineMetric.REVIEW_PAGE]?.count ?: 0)
        compose.onNodeWithText("待确认 55 条").performClick()
        compose.waitUntil(5000) { (NotificationMetrics.snapshot()[PipelineMetric.REVIEW_PAGE]?.count ?: 0) > 0 }
        compose.waitForIdle()
        val count = NotificationMetrics.snapshot()[PipelineMetric.REVIEW_COUNT]?.count
        val pages = NotificationMetrics.snapshot()[PipelineMetric.REVIEW_PAGE]?.count
        compose.runOnIdle {
            val changes = (context as MonthlyExpenseApplication).container.dataChanges
            repeat(100) { changes.publish(setOf(DataDomain.LEDGER)) }
        }
        compose.waitForIdle()
        assertEquals(count, NotificationMetrics.snapshot()[PipelineMetric.REVIEW_COUNT]?.count)
        assertEquals(pages, NotificationMetrics.snapshot()[PipelineMetric.REVIEW_PAGE]?.count)
        // Dispose and drain the collector before the next Robolectric/Compose test starts.
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
    }
}
