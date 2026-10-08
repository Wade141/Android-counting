package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.PaymentSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PaymentImportWorkerTest {
    @Test fun armingSameSourceGenerationDefersWithoutPermanentTombstone() = runBlocking {
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        container.notificationIntake.initialize()
        val policies = container.notificationIntake.policies
        val clock = com.example.monthlyexpense.notification.intake.IntakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), 1)
        policies.setEnabled(false, clock); policies.setEnabled(true, clock)
        val policy = policies.snapshot().sources.getValue(PaymentSource.WECHAT)
        val d = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(
            com.example.monthlyexpense.notification.RawNotification("com.tencent.mm", "微信支付", "已支付20元", "", emptyList(), 10000, "arming", null))
        val events = com.example.monthlyexpense.notification.repository.NotificationEventRepository(container.database)
        events.stageForScheduling(d, container.database.backupDao.notificationProtocol.bookGeneration(), policy.generation)
        val result = TestListenableWorkerBuilder<PaymentImportWorker>(context,
            inputData = androidx.work.workDataOf("event_id" to d.eventId)).build().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(events.stagedIds().contains(d.eventId))
        assertEquals(com.example.monthlyexpense.notification.repository.DispositionReason.NONE, events.dispositionReason(d.eventId))
    }

    @Test fun armingNewGenerationStillRejectsOldHandedOffEvent() = runBlocking {
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        container.notificationIntake.initialize()
        val policies = container.notificationIntake.policies
        val clock = com.example.monthlyexpense.notification.intake.IntakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), 1)
        policies.setEnabled(false, clock); policies.setEnabled(true, clock)
        val policy = policies.snapshot().sources.getValue(PaymentSource.WECHAT)
        val d = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(
            com.example.monthlyexpense.notification.RawNotification("com.tencent.mm", "微信支付", "已支付20元", "", emptyList(), 10000, "old-generation", null))
        val events = com.example.monthlyexpense.notification.repository.NotificationEventRepository(container.database)
        events.stageForScheduling(d, container.database.backupDao.notificationProtocol.bookGeneration(), policy.generation)
        policies.setEnabled(false, clock); policies.setEnabled(true, clock)
        TestListenableWorkerBuilder<PaymentImportWorker>(context,
            inputData = androidx.work.workDataOf("event_id" to d.eventId)).build().doWork()
        assertEquals(com.example.monthlyexpense.notification.repository.DispositionReason.SOURCE_DISABLED, events.dispositionReason(d.eventId))
        assertTrue(events.stagedIds().isEmpty())
    }
    @Test fun repeatedWorkDoesNotInvalidateLedgerAndReviewOnlyInvalidatesReview() = runBlocking {
        val changes = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container.dataChanges
        val before = changes.revisions.value
        val raw = com.example.monthlyexpense.notification.RawNotification(
            "com.tencent.mm", "微信支付", "成功转账20元", "", emptyList(), 10000, "review-retry", null)
        val decision = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(raw)
        stageCurrent(decision)
        repeat(20) {
            TestListenableWorkerBuilder<PaymentImportWorker>(context,
                inputData = androidx.work.workDataOf("event_id" to decision.eventId)).build().doWork()
        }
        assertEquals(before.ledger, changes.revisions.value.ledger)
        assertEquals(before.review + 1, changes.revisions.value.review)
    }
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDatabase() = runBlocking {
        context.deleteDatabase(DATABASE_NAME)
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        container.notificationIntake.initialize()
        val policies = container.notificationIntake.policies
        policies.setEnabled(true, com.example.monthlyexpense.notification.intake.IntakeClock(
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), 1))
        // Worker fixture: boundary encryption is covered by the intake tests, not Robolectric's Keystore.
        container.notificationIntake.inbox.database.writableDatabase.execSQL("UPDATE source_policies SET state='ON' WHERE selected=1")
        policies.reload()
        Unit
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun processesStagedEventWithoutRawNotificationInWorkData() = runBlocking {
        val raw = com.example.monthlyexpense.notification.RawNotification(
            "com.tencent.mm", "微信支付", "成功转账20元", "", emptyList(), 10000, "transfer", null)
        val decision = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(raw)
        stageCurrent(decision)
        val result = TestListenableWorkerBuilder<PaymentImportWorker>(context,
            inputData = androidx.work.workDataOf("event_id" to decision.eventId)).build().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        ExpenseDatabaseHelper(context).use {
            assertEquals(1, com.example.monthlyexpense.notification.repository.NotificationEventRepository(it).pending().size)
        }
    }

    @Test
    fun oldWorkerDoesNotDuplicateNewEventOrResurrectDeletedExpense() = runBlocking {
        val raw = com.example.monthlyexpense.notification.RawNotification("com.tencent.mm", "微信支付", "已支付20元", "", emptyList(), 10000, "old-key", null)
        val d = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(raw)
        ExpenseDatabaseHelper(context).use {
            val repo = com.example.monthlyexpense.notification.repository.NotificationEventRepository(it)
            repo.stage(d); repo.process(d.eventId)
        }
        val old = PaymentImportWorkData.create(ParsedPayment(PaymentSource.WECHAT, 2000, null, 10000, "old-key", 0.86f))
        suspend fun runOld() { assertTrue(TestListenableWorkerBuilder<PaymentImportWorker>(context, inputData = old).build().doWork() is ListenableWorker.Result.Success) }
        runOld()
        ExpenseDatabaseHelper(context).use {
            it.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            it.writableDatabase.delete("expenses", null, null)
        }
        runOld()
        ExpenseDatabaseHelper(context).use {
            it.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun legacyQueueWithoutGenerationRequiresReviewAndRepeatedWorkIsIdempotent() = runBlocking {
        val zone = ZoneId.systemDefault()
        val paymentDate = YearMonth.now(zone).plusMonths(1).atDay(15)
        val payment = ParsedPayment(
            source = PaymentSource.ALIPAY,
            amountCents = 2_500L,
            merchant = "示例商城",
            time = paymentDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            notificationKey = "synthetic-alipay-3",
            confidence = 0.93f
        )
        val input = PaymentImportWorkData.create(payment)

        repeat(2) {
            val result = TestListenableWorkerBuilder<PaymentImportWorker>(context, inputData = input)
                .build()
                .doWork()
            assertTrue(result is ListenableWorker.Result.Success)
        }

        ExpenseDatabaseHelper(context).use { database ->
            val records = database.expenseDao.currentMonthRecords(paymentDate)
                .filter { it.source == ExpenseSource.ALIPAY_AUTO }
            assertTrue(records.isEmpty())
            val pending = com.example.monthlyexpense.notification.repository.NotificationEventRepository(database).pending()
            assertEquals(1, pending.size)
            assertEquals(2_500L, pending.single().amountCents)
            assertEquals("示例商城", pending.single().merchant)
            assertTrue("legacy_generation_unknown" in pending.single().reasons)
        }
    }

    @Test fun delayedLegacyQueueCannotAutomaticallyEnterRestoredBook() = runBlocking {
        val input = PaymentImportWorkData.create(ParsedPayment(PaymentSource.WECHAT, 1900, "旧付款", 10000, "before-restore", 0.9f))
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        val previous = container.database.backupDao.notificationProtocol.bookGeneration()
        assertTrue(container.database.backupDao.restoreBackupDatabase(container.database.backupDao.exportBackupDatabase()))
        assertTrue(previous != container.database.backupDao.notificationProtocol.bookGeneration())
        val result = TestListenableWorkerBuilder<PaymentImportWorker>(context, inputData = input).build().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        container.database.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(1, com.example.monthlyexpense.notification.repository.NotificationEventRepository(container.database).pendingCount())
    }

    @Test fun delayedLegacyQueueCannotAutomaticallyCrossSourceOffOnBoundary() = runBlocking {
        val input = PaymentImportWorkData.create(ParsedPayment(PaymentSource.WECHAT, 1900, "关闭前付款", 10000, "before-toggle", 0.9f))
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        val policies = container.notificationIntake.policies
        val clock = com.example.monthlyexpense.notification.intake.IntakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), 1)
        policies.setEnabled(false, clock); policies.setEnabled(true, clock)
        container.notificationIntake.inbox.database.writableDatabase.execSQL("UPDATE source_policies SET state='ON' WHERE selected=1")
        policies.reload()
        val result = TestListenableWorkerBuilder<PaymentImportWorker>(context, inputData = input).build().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        container.database.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(1, com.example.monthlyexpense.notification.repository.NotificationEventRepository(container.database).pendingCount())
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
    }

    private fun stageCurrent(decision: com.example.monthlyexpense.notification.decision.PaymentDecision) {
        val container = (context.applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container
        val generation = container.notificationIntake.policies.snapshot().sources.getValue(requireNotNull(decision.source)).generation
        com.example.monthlyexpense.notification.repository.NotificationEventRepository(container.database)
            .stageForScheduling(decision, container.database.backupDao.notificationProtocol.bookGeneration(), generation)
    }
}
