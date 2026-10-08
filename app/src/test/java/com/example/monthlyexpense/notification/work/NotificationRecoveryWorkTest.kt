package com.example.monthlyexpense.notification.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = com.example.monthlyexpense.MonthlyExpenseApplication::class)
class NotificationRecoveryWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun initialize() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun close() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test fun enabledSettingSchedulesOnePeriodicCheckAndDisablingCancelsIt() = runBlocking {
        val container = (context as com.example.monthlyexpense.MonthlyExpenseApplication).container
        container.notificationIntake.initialize()
        val settings = container.autoSettings
        settings.isEnabled = true
        repeat(3) { NotificationRecoveryWork.synchronize(context) }
        val manager = WorkManager.getInstance(context)
        val active = manager.getWorkInfosForUniqueWork(NotificationRecoveryWork.PERIODIC_NAME).get()
        assertEquals(1, active.size)
        assertFalse(active.single().state.isFinished)
        settings.isEnabled = false
        NotificationRecoveryWork.synchronize(context)
        assertEquals(WorkInfo.State.CANCELLED,
            manager.getWorkInfosForUniqueWork(NotificationRecoveryWork.PERIODIC_NAME).get().single().state)
        NotificationRecoveryWork.afterSystemRestart(context)
        assertTrue(manager.getWorkInfosForUniqueWork("notification_listener_startup").get().all { it.state.isFinished })
    }

    @Test fun replayResultSurvivesControllerRecreationAndDismissalDoesNotReappear() = runBlocking {
        AutoBookkeepingSettings(context).isEnabled = false
        context.getSharedPreferences(NotificationReplayRequests.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("result_id", "legacy-result").putString("result_message", "请先开启自动记账。")
            .putBoolean("result_completed", false).commit()
        val restored = NotificationReplayRequests(context)
        val result = withTimeout(5_000) { restored.state.first { it.resultId != null } }
        assertEquals("请先开启自动记账。", result.message)
        assertFalse(result.completed)
        WorkManager.getInstance(context).pruneWork().result.get()
        val afterPruning = withTimeout(5_000) { NotificationReplayRequests(context).state.first() }
        assertEquals(result.message, afterPruning.message)
        assertEquals(result.resultId, afterPruning.resultId)
        restored.dismiss(result.resultId!!)
        val dismissed = withTimeout(5_000) { NotificationReplayRequests(context).state.first() }
        assertNull(dismissed.message)
        assertFalse(dismissed.busy)
    }

    @Test fun durableOperationReportsProcessingThenCommittedResultAndKeepsDismissal() = runBlocking {
        val app = context as com.example.monthlyexpense.MonthlyExpenseApplication
        app.container.notificationIntake.initialize()
        val store = app.container.replayOperations
        val op = store.create(manual = true, bookGeneration = app.container.database.backupDao.notificationProtocol.bookGeneration())
        store.beginAttempt(op.operationId)
        store.confirmConnection(op.operationId, 4)
        val requests = NotificationReplayRequests(context)
        val processing = withTimeout(5_000) { requests.state.first { it.operation != null } }
        assertTrue(processing.busy)
        assertFalse(processing.completed)
        assertNull(com.example.monthlyexpense.notification.replay.ReplayResultPresentation.from(
            processing.operation!!, com.example.monthlyexpense.notification.replay.ConnectionStatus.CONNECTED))
        store.setFence(op.operationId, "session", 0)
        com.example.monthlyexpense.notification.replay.ReplayCollectionSource.entries.forEach {
            store.markCollected(op.operationId, it)
        }
        store.sealCollection(op.operationId)
        store.finish(op.operationId)
        val result = withTimeout(5_000) { NotificationReplayRequests(context).state.first { it.completed } }
        assertFalse(result.busy)
        requests.dismiss(op.operationId)
        val dismissed = withTimeout(5_000) { NotificationReplayRequests(context).state.first { it.operation?.dismissed == true } }
        assertNull(com.example.monthlyexpense.notification.replay.ReplayResultPresentation.from(
            dismissed.operation!!, com.example.monthlyexpense.notification.replay.ConnectionStatus.CONNECTED))
    }

    @Test fun popupReconcilesCommittedBookingBeforeShowingTerminalResult() = runBlocking {
        val app = context as com.example.monthlyexpense.MonthlyExpenseApplication
        app.container.notificationIntake.initialize()
        val database = app.container.database
        val book = database.backupDao.notificationProtocol.bookGeneration()
        val events = com.example.monthlyexpense.notification.repository.NotificationEventRepository(database)
        val decision = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine().evaluate(
            com.example.monthlyexpense.notification.RawNotification(
                com.example.monthlyexpense.notification.PaymentPackages.WECHAT, "微信支付", "已支付20元", "", emptyList(),
                System.currentTimeMillis(), "receipt-popup", null))
        events.stageForScheduling(decision, book, 1L)
        val store = app.container.replayOperations
        val operation = store.create(manual = true, bookGeneration = book)
        store.beginAttempt(operation.operationId)
        store.registerItems(operation.operationId, listOf(
            com.example.monthlyexpense.notification.replay.ReplayOperationItem("item", decision.eventId)))
        com.example.monthlyexpense.notification.repository.ReplayReceiptRepository(database, events)
            .processForReplay(operation.operationId, "item", decision.eventId, book, 1L)
        store.finish(operation.operationId, com.example.monthlyexpense.notification.replay.ReplayTerminationReason.STORAGE)
        assertEquals(0, store.get(operation.operationId)!!.summary.booked)
        val result = withTimeout(5_000) { NotificationReplayRequests(context).state.first { it.operation != null } }
        assertEquals(1, result.operation!!.summary.booked)
        assertFalse(result.completed)
    }
}
