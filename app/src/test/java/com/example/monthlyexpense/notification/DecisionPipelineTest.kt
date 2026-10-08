package com.example.monthlyexpense.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecisionPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun setup() { context.deleteDatabase("expenses.db") }
    @After fun cleanup() { context.deleteDatabase("expenses.db") }
    @Test fun cancellingBatchKeepsCommittedEntryAndDoesNotProcessNextOne() {
        ExpenseDatabaseHelper(context).use { db ->
            val pipeline = NotificationDecisionProcessor(PaymentDecisionEngine(), NotificationEventRepository(db))
            val raw = RawNotification(PaymentPackages.WECHAT, "微信支付", "已支付20元", "", emptyList(), 10000, "first", null)
            var checked = 0
            try {
                pipeline.replay(listOf(raw, raw.copy(notificationKey = "second", postTime = 11000)), 12000, { true }) {
                    if (++checked == 2) throw kotlinx.coroutines.CancellationException()
                }
                fail("expected cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, checked - 1)
            val after = pipeline.replay(listOf(raw), 12000) { true }
            assertEquals(1, after.existing)
            assertEquals(0, after.inserted)
        }
    }
    @Test fun realtimeAndReplayUseSameEventAndReviewCount() {
        ExpenseDatabaseHelper(context).use { db ->
            val pipeline = NotificationDecisionProcessor(PaymentDecisionEngine(), NotificationEventRepository(db))
            val raw = RawNotification(PaymentPackages.WECHAT, "微信支付", "已支付20元", "", emptyList(), 10000, "k", null)
            val staged = pipeline.stage(raw)
            assertEquals(StoreResult.STAGED, staged.second)
            val replay = pipeline.replay(listOf(raw, raw.copy(text = "成功转账20元", notificationKey = "transfer")), 12000) { true }
            assertEquals(1, replay.inserted)
            assertEquals(1, replay.review)
            assertEquals(StoreResult.DUPLICATE, pipeline.process(staged.first.eventId))
            assertEquals(1, NotificationEventRepository(db).pending().size)
        }
    }
}
