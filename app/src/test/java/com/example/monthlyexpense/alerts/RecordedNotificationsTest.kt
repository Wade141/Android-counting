package com.example.monthlyexpense.alerts

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.*
import com.example.monthlyexpense.notification.*
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecordedNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ExpenseDatabaseHelper
    @Before fun setup() { context.deleteDatabase("expenses.db"); db=ExpenseDatabaseHelper(context) }
    @After fun close() { db.close(); context.deleteDatabase("expenses.db") }
    private fun decision(key: String = "notice") = PaymentDecisionEngine().evaluate(
        RawNotification(PaymentPackages.WECHAT,"微信支付","向示例便利店付款成功，金额20元","",emptyList(),10000,key,null))

    @Test fun publishesOnlyAfterCommitAndNeverForDuplicateOrRollback() {
        val notices=mutableListOf<AppNotice>()
        val publisher=AppNotifications(context, NotificationSink { notices += it; NoticeDelivery.POSTED })
        val events=NotificationEventRepository(db).apply {
            onExpenseRecorded = { eventId ->
                assertFalse(db.writableDatabase.inTransaction())
                publisher.expenseRecorded(db,eventId)
            }
        }
        val d=decision(); events.stage(d)
        db.writableDatabase.execSQL("CREATE TRIGGER reject_notice_test BEFORE INSERT ON expenses BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching {events.process(d.eventId)}.isFailure)
        assertTrue(notices.isEmpty())
        db.writableDatabase.execSQL("DROP TRIGGER reject_notice_test")
        assertEquals(StoreResult.INSERTED,events.process(d.eventId))
        repeat(3) {events.process(d.eventId)}
        assertEquals(1,notices.size)
        assertTrue(notices.single().title.contains("已记账"))
        assertTrue(notices.single().body.contains("20.00"))
        assertTrue(notices.single().body.contains("其他"))
        val target=(notices.single().destination as NoticeDestination.EditExpense).target
        assertEquals("示例便利店",db.expenseDao.findById(target.expenseId)?.name)
        assertEquals(db.backupDao.notificationProtocol.bookGeneration(),target.bookGeneration)
    }

    @Test fun replayPublishesOnceOnlyAfterItsOuterTransactionCommits() {
        val ids=mutableListOf<String>()
        val events=NotificationEventRepository(db).apply {onExpenseRecorded={ assertFalse(db.writableDatabase.inTransaction());ids+=it }}
        val d=decision();events.stage(d)
        val receipts=ReplayReceiptRepository(db,events)
        receipts.processForReplay("operation","item",d.eventId)
        receipts.processForReplay("operation","item",d.eventId)
        assertEquals(listOf(d.eventId),ids)
    }

    @Test fun failedNotificationDoesNotTurnCommittedExpenseIntoFailedImport() {
        val events=NotificationEventRepository(db).apply {onExpenseRecorded={error("notifications unavailable")}}
        val d=decision();events.stage(d)
        assertEquals(StoreResult.INSERTED,events.process(d.eventId))
        assertEquals(StoreResult.DUPLICATE,events.process(d.eventId))
    }

    @Test fun differentBillsHaveIndependentImmutableEditIntentsAndBudgetChannelIsSeparate() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val sink=AndroidNotificationSink(context)
        val first=RecordedExpenseTarget("book",1)
        val second=RecordedExpenseTarget("book",2)
        assertEquals(NoticeDelivery.POSTED,sink.post(AppNotice(NoticeKind.RECORDED,"one","已记账","20.00",NoticeDestination.EditExpense(first))))
        sink.post(AppNotice(NoticeKind.RECORDED,"two","已记账","30.00",NoticeDestination.EditExpense(second)))
        val manager=context.getSystemService(NotificationManager::class.java)
        val posted=shadowOf(manager).allNotifications
        assertEquals(2,posted.size)
        val targets=posted.map { RecordedExpenseTarget.fromUri(shadowOf(it.contentIntent).savedIntent.data) }.toSet()
        assertEquals(setOf(first,second),targets)
        assertTrue(posted.all { shadowOf(it.contentIntent).isImmutable })
        assertNotNull(manager.getNotificationChannel(NoticeKind.BUDGET.channelId))
        assertNotEquals(NoticeKind.BUDGET.channelId,NoticeKind.RECORDED.channelId)
    }

    @Test fun blockedChannelIsReportedAndDoesNotPost() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val sink=AndroidNotificationSink(context);sink.ensureChannels()
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(NoticeKind.RECORDED.channelId)
        channel.importance=NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertEquals(NoticeDelivery.DISABLED,sink.post(AppNotice(NoticeKind.RECORDED,"id","已记账","文字",NoticeDestination.Home)))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test @org.robolectric.annotation.Config(sdk=[33])
    fun notificationPermissionDenialDoesNotPretendToSendAndGrantAllowsSending() {
        val sink=AndroidNotificationSink(context)
        val notice=AppNotice(NoticeKind.RECORDED,"permission","已记账","内容",NoticeDestination.Home)
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NoticeDelivery.DISABLED,sink.post(notice))
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NoticeDelivery.POSTED,sink.post(notice))
    }
}
