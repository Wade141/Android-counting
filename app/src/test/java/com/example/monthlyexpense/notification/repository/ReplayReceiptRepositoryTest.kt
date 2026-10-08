package com.example.monthlyexpense.notification.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayReceiptRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: ExpenseDatabaseHelper
    private lateinit var events: NotificationEventRepository
    private lateinit var receipts: ReplayReceiptRepository
    private val changes = mutableListOf<Set<DataDomain>>()
    @Before fun setup() {
        context.deleteDatabase("expenses.db")
        helper = ExpenseDatabaseHelper(context)
        events = NotificationEventRepository(helper) { if (it.isNotEmpty()) changes += it }
        receipts = ReplayReceiptRepository(helper, events)
    }
    @After fun close() { helper.close(); context.deleteDatabase("expenses.db") }
    private fun decision() = PaymentDecisionEngine().evaluate(RawNotification(
        PaymentPackages.WECHAT, "微信支付", "向便利店付款成功，金额20元", "", emptyList(), 10000, "receipt-key", null))
    private fun count(table: String) = helper.readableDatabase.rawQuery("SELECT count(*) FROM $table", null)
        .use { it.moveToFirst(); it.getInt(0) }

    @Test fun receiptFailureRollsBackExpenseEventAndPublication() {
        val d = decision(); events.stage(d)
        helper.writableDatabase.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON replay_item_receipts BEGIN SELECT RAISE(ABORT, 'receipt failure'); END")
        assertTrue(runCatching { receipts.processForReplay("op", "item", d.eventId) }.isFailure)
        assertEquals(0, count("expenses")); assertEquals(0, count("replay_item_receipts"))
        assertTrue(events.stageForScheduling(d).shouldSchedule); assertTrue(changes.isEmpty())
    }

    @Test fun restartAndDuplicateItemsRetainOriginalInsertedAttribution() {
        val d = decision(); events.stage(d)
        assertEquals(StoreResult.INSERTED, receipts.processForReplay("op", "one", d.eventId).result)
        val reopened = ReplayReceiptRepository(helper, events)
        assertEquals(StoreResult.INSERTED, reopened.processForReplay("op", "two", d.eventId).result)
        assertEquals(1, reopened.receipts("op").size); assertEquals(1, count("expenses"))
        assertEquals(1, changes.size)
    }

    @Test fun realtimeWinnerCannotBeCreditedToReplay() {
        val d = decision(); events.stage(d); events.process(d.eventId)
        assertEquals(StoreResult.DUPLICATE, receipts.processForReplay("op", "item", d.eventId).result)
        assertEquals(1, count("expenses"))
    }

    @Test fun terminalCleanupRemovesOnlyRequestedOperationReceipts() {
        val d = decision(); events.stage(d)
        receipts.processForReplay("closed", "one", d.eventId)
        receipts.processForReplay("active", "two", d.eventId)
        receipts.deleteOperations(listOf("closed"))
        assertTrue(receipts.receipts("closed").isEmpty())
        assertEquals(1, receipts.receipts("active").size)
        assertEquals(1, count("expenses"))
    }

    @Test fun callerOwnedRollbackDoesNotPublishChanges() {
        val d = decision(); events.stage(d)
        helper.writableDatabase.beginTransaction()
        try { receipts.processForReplay("op", "item", d.eventId); assertTrue(changes.isEmpty()) }
        finally { helper.writableDatabase.endTransaction() }
        assertEquals(0, count("expenses")); assertEquals(0, count("replay_item_receipts"))
        assertTrue(changes.isEmpty())
    }

    @Test fun restoredBookRejectsOldOperationWithoutWritingReceipt() {
        val d = decision(); events.stage(d)
        val protocol = LedgerNotificationProtocol(helper)
        val previous = protocol.bookGeneration()
        helper.writableDatabase.beginTransaction()
        try {
            protocol.markRestoreCommitted(helper.writableDatabase, "restore", "new-book", "{}")
            helper.writableDatabase.setTransactionSuccessful()
        } finally { helper.writableDatabase.endTransaction() }
        assertTrue(runCatching { receipts.processForReplay("op", "item", d.eventId, previous) }.isFailure)
        assertEquals(0, count("expenses")); assertEquals(0, count("replay_item_receipts"))
    }
}
