package com.example.monthlyexpense.notification.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.decision.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationEventRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ExpenseDatabaseHelper
    private lateinit var repository: NotificationEventRepository
    @Before fun setup() { context.deleteDatabase("expenses.db"); db = ExpenseDatabaseHelper(context); repository = NotificationEventRepository(db) }
    @After fun close() { db.close(); context.deleteDatabase("expenses.db") }
    private fun decision(text: String = "向便利店付款成功，金额20元", time: Long = 10000, key: String = "key") =
        PaymentDecisionEngine().evaluate(RawNotification(PaymentPackages.WECHAT, "微信支付", text, "", emptyList(), time, key, null))
    private fun count() = db.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun rolledBackExpenseDoesNotPublishAndRetryPublishesAfterCommit() {
        val changes = mutableListOf<Set<com.example.monthlyexpense.data.DataDomain>>()
        val observed = NotificationEventRepository(db) { domains ->
            if (domains.isNotEmpty()) { assertEquals(1, count()); changes += domains }
        }
        val d = decision()
        observed.stage(d)
        db.writableDatabase.execSQL("CREATE TRIGGER reject_expense BEFORE INSERT ON expenses BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        runCatching { observed.process(d.eventId) }
        assertTrue(changes.isEmpty())
        assertEquals(0, count())
        assertTrue(observed.stageForScheduling(d).shouldSchedule)
        db.writableDatabase.execSQL("DROP TRIGGER reject_expense")
        assertEquals(StoreResult.INSERTED, observed.process(d.eventId))
        assertEquals(listOf(setOf(com.example.monthlyexpense.data.DataDomain.LEDGER)), changes)
    }

    @Test fun changesDescribeActualTransitionsRatherThanResultNames() {
        val d = decision("成功转账20元")
        repository.stage(d)
        assertEquals(setOf(com.example.monthlyexpense.data.DataDomain.REVIEW), repository.processWithChanges(d.eventId).changedDomains)
        assertTrue(repository.processWithChanges(d.eventId).changedDomains.isEmpty())
        db.expenseDao.addExpense(2000, com.example.monthlyexpense.BuiltInCategoryKeys.OTHER, "已有", "")
        val id = db.readableDatabase.rawQuery("SELECT id FROM expenses", null).use { it.moveToFirst(); it.getLong(0) }
        val linked = repository.linkWithChanges(d.eventId, id)
        assertEquals(StoreResult.DUPLICATE, linked.result)
        assertEquals(setOf(com.example.monthlyexpense.data.DataDomain.REVIEW), linked.changedDomains)
        assertTrue(repository.linkWithChanges(d.eventId, id).changedDomains.isEmpty())
    }

    @Test fun completedStageDoesNotScheduleButUnprocessedConflictDoes() {
        val d = decision()
        assertTrue(repository.stageForScheduling(d).shouldSchedule)
        assertTrue(repository.stageForScheduling(d).shouldSchedule)
        assertEquals(setOf(com.example.monthlyexpense.data.DataDomain.LEDGER), repository.processWithChanges(d.eventId).changedDomains)
        repeat(100) { assertFalse(repository.stageForScheduling(d).shouldSchedule) }
    }

    @Test fun stableRecoveryAndReviewPagesDoNotLoseEqualTimestamps() {
        repeat(503) { repository.stage(decision("成功转账20元", key = "page-$it")) }
        val first = repository.stagedPage()
        assertEquals(500, first.size)
        val second = repository.stagedPage(first.last())
        assertEquals(3, second.size)
        assertEquals(503, (first + second).map { it.eventId }.toSet().size)
        (first + second).forEach { repository.process(it.eventId) }
        assertEquals(503, repository.pendingCount())
        val page = repository.pendingPage()
        assertEquals(50, page.size)
        val next = repository.pendingPage(page.last())
        assertEquals(100, (page + next).map { it.eventId }.toSet().size)
    }

    @Test fun stageProcessRetryAndDeleteReplayAreIdempotent() {
        val d = decision()
        repository.stage(d)
        assertEquals(0, count())
        assertEquals(StoreResult.INSERTED, repository.process(d.eventId))
        assertEquals(StoreResult.DUPLICATE, repository.process(d.eventId))
        db.writableDatabase.delete("expenses", null, null)
        repository.stage(d.copy(ruleVersion = "later"))
        assertEquals(StoreResult.DUPLICATE, repository.process(d.eventId))
        assertEquals(0, count())
    }

    @Test fun similarPaymentsAreReviewableAndCanBothBeKept() {
        val first = decision()
        repository.stage(first); repository.process(first.eventId)
        val second = decision(time = 11000, key = "new-key")
        repository.stage(second)
        assertEquals(StoreResult.REVIEW, repository.process(second.eventId))
        assertEquals(1, count())
        assertEquals(1, repository.pending().size)
        assertEquals(StoreResult.INSERTED, repository.confirm(second.eventId, 2000, "便利店"))
        assertEquals(StoreResult.DUPLICATE, repository.confirm(second.eventId, 2000, "便利店"))
        assertEquals(2, count())
    }

    @Test fun ambiguousCandidatesAreNotExpensesAndIgnoreSurvivesReplay() {
        val d = decision("支付成功，金额18元，金额20元")
        repository.stage(d)
        assertEquals(StoreResult.REVIEW, repository.process(d.eventId))
        assertEquals(0, count())
        assertEquals(StoreResult.REJECTED, repository.confirm(d.eventId, -1, "test"))
        assertEquals(1, repository.pending().size)
        assertEquals(StoreResult.IGNORED, repository.ignore(d.eventId))
        repository.stage(d)
        assertEquals(StoreResult.DUPLICATE, repository.process(d.eventId))
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun linkOnlyExistingExpenseAndConfirmationAtomic() {
        val d = decision("成功转账20元")
        repository.stage(d); repository.process(d.eventId)
        assertEquals(StoreResult.REJECTED, repository.link(d.eventId, 99999))
        assertEquals(StoreResult.INSERTED, repository.confirm(d.eventId, 2500, "聚餐"))
        assertEquals(1, count())
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun migratesVersionFiveWithoutChangingExpenses() {
        db.expenseDao.addExpense(1234, com.example.monthlyexpense.BuiltInCategoryKeys.OTHER, "旧记录", "")
        db.writableDatabase.execSQL("DROP TABLE notification_events")
        db.writableDatabase.version = 5
        com.example.monthlyexpense.classification.removeClassificationFromLegacyFixture(db.writableDatabase)
        db.close()
        db = ExpenseDatabaseHelper(context); repository = NotificationEventRepository(db)
        assertTrue(repository.pending().isEmpty())
        assertEquals(1, count())
        db.readableDatabase.rawQuery("SELECT amount_cents,name FROM expenses", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1234L, it.getLong(0)); assertEquals("旧记录", it.getString(1))
        }
    }

    @Test fun restoringBackupCancelsPendingEventAndDetachesOldExpenseLinks() {
        val backup = db.backupDao.exportBackupDatabase()
        val d = decision(); repository.stage(d)
        assertTrue(db.backupDao.restoreBackupDatabase(backup))
        assertEquals(StoreResult.DUPLICATE, repository.process(d.eventId))
        assertEquals(0, count())
        assertTrue(repository.stagedIds().isEmpty())
    }

    @Test fun explicitTransactionIdsDistinguishRealConsecutivePaymentsAndDeduplicateUpdates() {
        val first = decision("向便利店付款成功，金额20元，交易单号：A123456789")
        repository.stage(first); assertEquals(StoreResult.INSERTED, repository.process(first.eventId))
        val second = decision("向便利店付款成功，金额20元，交易单号：B123456789", time = 11000)
        repository.stage(second); assertEquals(StoreResult.INSERTED, repository.process(second.eventId))
        val update = decision("支付成功，金额20元，交易单号：A123456789", time = 20000)
        repository.stage(update); assertEquals(StoreResult.DUPLICATE, repository.process(update.eventId))
        assertEquals(2, count())
    }

    @Test fun sameNotificationUpdateOutsideThreeSecondWindowNeedsReview() {
        val first = decision(); repository.stage(first); repository.process(first.eventId)
        val later = decision(time = 20000); repository.stage(later)
        assertEquals(StoreResult.REVIEW, repository.process(later.eventId))
        assertEquals(1, count())
    }

    @Test fun ignoredDecisionCannotBePromotedByRulesOnReplay() {
        val d = decision("退款成功18元")
        assertEquals(StoreResult.IGNORED, repository.stage(d))
        repository.stage(d.copy(action = DecisionAction.AUTO, amountCents = 1800, ruleVersion = "changed"))
        assertEquals(StoreResult.DUPLICATE, repository.process(d.eventId))
        assertEquals(0, count())
    }

    @Test fun pendingTransactionDoesNotSuppressItsLaterActualPayment() {
        val pending = decision("明天自动扣款20元，交易单号：A123456789")
        repository.stage(pending)
        val completed = decision("已支付20元，交易单号：A123456789", time = 50000)
        repository.stage(completed)
        assertEquals(StoreResult.INSERTED, repository.process(completed.eventId))
    }

    @Test fun twoPendingReviewsForSameTransactionConfirmOnlyOnce() {
        val first = decision("成功转账20元，交易单号：A123456789")
        val second = decision("成功转账20元，交易单号：A123456789", time = 20000)
        repository.stage(first); repository.process(first.eventId)
        repository.stage(second); repository.process(second.eventId)
        assertEquals(StoreResult.INSERTED, repository.confirm(first.eventId, 2000, "转账支出"))
        assertEquals(StoreResult.DUPLICATE, repository.confirm(second.eventId, 2000, "转账支出"))
        assertEquals(1, count())
    }

    @Test fun sameTimestampAndAmountButDifferentTransactionIdsRemainSeparate() {
        val first = decision("已支付20元，交易单号：A123456789")
        val second = decision("已支付20元，交易单号：B123456789")
        repository.stage(first); repository.process(first.eventId)
        repository.stage(second)
        assertEquals(StoreResult.INSERTED, repository.process(second.eventId))
        assertEquals(2, count())
    }

    @Test fun restoredLegacyAdapterExpenseIsRecognizedWithoutEventTableHistory() {
        val legacy = com.example.monthlyexpense.notification.decision.legacyPaymentDecision(
            com.example.monthlyexpense.notification.parser.ParsedPayment(
                com.example.monthlyexpense.notification.parser.PaymentSource.WECHAT, 2000, null, 10000, "key", 0.85f))
        repository.stage(legacy); repository.process(legacy.eventId)
        val backup = db.backupDao.exportBackupDatabase()
        db.close(); context.deleteDatabase("expenses.db")
        db = ExpenseDatabaseHelper(context); repository = NotificationEventRepository(db)
        db.backupDao.restoreBackupDatabase(backup)
        val raw = decision("已支付20元")
        repository.stage(raw)
        assertEquals(StoreResult.DUPLICATE, repository.process(raw.eventId))
        assertEquals(1, count())
    }

    @Test fun concurrentConfirmationsProduceOnlyOneExpense() {
        val d = decision("成功转账20元")
        repository.stage(d); repository.process(d.eventId)
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<StoreResult> { start.await(); repository.confirm(d.eventId, 2000, "已核对支出") }
            }
            start.countDown()
            val outcomes = results.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == StoreResult.INSERTED })
            assertEquals(1, outcomes.count { it == StoreResult.DUPLICATE })
            assertEquals(1, count())
        } finally { executor.shutdownNow() }
    }
}
