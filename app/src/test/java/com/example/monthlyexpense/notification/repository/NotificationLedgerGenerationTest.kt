package com.example.monthlyexpense.notification.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationLedgerGenerationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: ExpenseDatabaseHelper
    private lateinit var events: NotificationEventRepository
    private lateinit var protocol: LedgerNotificationProtocol
    @Before fun setup() { context.deleteDatabase("expenses.db"); helper = ExpenseDatabaseHelper(context)
        events = NotificationEventRepository(helper); protocol = LedgerNotificationProtocol(helper) }
    @After fun close() { helper.close(); context.deleteDatabase("expenses.db") }
    private fun decision(key: String = "one", text: String = "成功转账20元") = PaymentDecisionEngine().evaluate(
        RawNotification(PaymentPackages.WECHAT, "微信支付", text, "", emptyList(), 10000, key, null))
    private fun count() = helper.readableDatabase.rawQuery("SELECT count(*) FROM expenses", null).use { it.moveToFirst(); it.getInt(0) }
    private fun restore() {
        helper.writableDatabase.beginTransaction()
        try { protocol.markRestoreCommitted(helper.writableDatabase, "restore", "restored-generation", "{}"); helper.writableDatabase.setTransactionSuccessful() }
        finally { helper.writableDatabase.endTransaction() }
    }

    @Test fun sourceOffThenOnRejectsPreviouslyHandedOffEvent() {
        val d = decision(text = "向便利店付款成功，金额20元")
        events.stageForScheduling(d, protocol.bookGeneration(), 1)
        assertEquals(StoreResult.REJECTED, events.process(d.eventId, protocol.bookGeneration(), 3))
        assertEquals(DispositionReason.SOURCE_DISABLED, events.dispositionReason(d.eventId))
        assertEquals(0, count())
    }

    @Test fun restoreInvalidatesPendingOnlyAndSingleConfirmationIsIdempotent() {
        val ignored = decision("ignored"); val candidate = decision("candidate"); val untouched = decision("untouched")
        listOf(ignored, candidate, untouched).forEach { events.stage(it); events.process(it.eventId) }
        events.ignore(ignored.eventId); restore()
        assertEquals(DispositionReason.USER_IGNORED, events.dispositionReason(ignored.eventId))
        assertEquals(DispositionReason.RESTORE_INVALIDATED, events.dispositionReason(candidate.eventId))
        val first = events.confirmQuarantined(candidate, protocol.bookGeneration(), 2, 2000, "已核对")
        assertEquals(StoreResult.INSERTED, first.result)
        assertEquals(StoreResult.DUPLICATE, events.confirmQuarantined(candidate, protocol.bookGeneration(), 2, 2000, "已核对").result)
        assertEquals(DispositionReason.RESTORE_INVALIDATED, events.dispositionReason(untouched.eventId))
        assertEquals(DispositionReason.USER_IGNORED, events.confirmQuarantined(ignored, protocol.bookGeneration(), 2, 2000, "已核对").reason)
        assertEquals(1, count())
    }

    @Test fun confirmationRejectsStalePageAndReceiptFailureRollsBack() {
        val d = decision(); events.stage(d); events.process(d.eventId)
        val old = protocol.bookGeneration(); restore()
        assertEquals(StoreResult.REJECTED, events.confirmQuarantined(d, old, 1, 2000, "核对").result)
        helper.writableDatabase.execSQL("CREATE TRIGGER reject_confirmation BEFORE INSERT ON quarantine_confirmation_receipts BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertTrue(runCatching { events.confirmQuarantined(d, protocol.bookGeneration(), 2, 2000, "核对") }.isFailure)
        assertEquals(DispositionReason.RESTORE_INVALIDATED, events.dispositionReason(d.eventId))
        assertEquals(0, count())
    }

    @Test fun ruleAndUnknownTombstonesNeverRevive() {
        val rule = decision("rule", "明天自动扣款20元"); events.stage(rule)
        val unknown = decision("unknown"); events.stage(unknown)
        helper.writableDatabase.execSQL("UPDATE notification_events SET state='IGNORED', disposition_reason='LEGACY_UNKNOWN' WHERE event_id = ?", arrayOf(unknown.eventId))
        assertEquals(DispositionReason.RULE_IGNORED, events.confirmQuarantined(rule, protocol.bookGeneration(), 1, 2000, "核对").reason)
        assertEquals(DispositionReason.LEGACY_UNKNOWN, events.confirmQuarantined(unknown, protocol.bookGeneration(), 1, 2000, "核对").reason)
        assertEquals(0, count())
    }

    @Test fun deletedRecordedTombstoneRequiresManualEntry() {
        val d = decision(text = "向便利店付款成功，金额20元")
        events.stage(d); events.process(d.eventId)
        helper.writableDatabase.delete("expenses", null, null)
        restore()
        assertEquals(StoreResult.REJECTED, events.confirmQuarantined(d, protocol.bookGeneration(), 2, 2000, "核对").result)
        assertEquals(0, count())
    }

    @Test fun ignoredQuarantineCannotReviveThroughAnotherInputForSameEvent() {
        val d = decision(); events.stage(d); restore()
        assertEquals(StoreResult.IGNORED, events.ignoreQuarantined(d, protocol.bookGeneration(), 2))
        assertEquals(DispositionReason.RESTORE_INVALIDATED, events.dispositionReason(d.eventId))
        assertEquals(DispositionReason.USER_IGNORED, events.confirmQuarantined(d, protocol.bookGeneration(), 2, 2000, "核对").reason)
        assertEquals(0, count())
    }

    @Test fun knownEventCanBeIgnoredAfterPayloadBecomesUnavailable() {
        val d = decision(); events.stage(d); restore()
        assertEquals(StoreResult.IGNORED, events.ignoreQuarantinedEvent(d.eventId, protocol.bookGeneration()))
        assertEquals(DispositionReason.USER_IGNORED, events.confirmQuarantined(d, protocol.bookGeneration(), 2, 2000, "核对").reason)
        assertEquals(DispositionReason.RESTORE_INVALIDATED, events.dispositionReason(d.eventId))
        assertEquals(0, count())
    }

    @Test fun generationAndRestoreMarkerRollBackTogether() {
        val d = decision(); events.stage(d); events.process(d.eventId)
        val previous = protocol.bookGeneration()
        helper.writableDatabase.beginTransaction()
        try { protocol.markRestoreCommitted(helper.writableDatabase, "aborted", "not-committed", "{\"wechat\":false}") }
        finally { helper.writableDatabase.endTransaction() }
        assertEquals(previous, protocol.bookGeneration()); assertNull(protocol.latestRestore())
        assertEquals(1, events.pendingCount()); assertEquals(DispositionReason.NONE, events.dispositionReason(d.eventId))
    }

    @Test fun v6MigrationKeepsUnknownIgnoresAndDefersPendingGenerationBinding() {
        val ignored = decision("ignored"); val pending = decision("pending")
        events.stage(ignored); events.process(ignored.eventId); events.ignore(ignored.eventId)
        events.stage(pending)
        val db = helper.writableDatabase
        db.execSQL("""CREATE TABLE old_events AS SELECT event_id, notification_identity, source, observed_at,
            action, state, amount_cents, amounts, merchant, kind, reasons, rule_version, legacy_event_id,
            legacy_notification_key, transaction_ref, occurred_at, time_origin, expense_id FROM notification_events""")
        db.execSQL("DROP TABLE notification_events")
        db.execSQL("ALTER TABLE old_events RENAME TO notification_events")
        listOf("ledger_metadata", "restore_operation", "replay_item_receipts", "quarantine_confirmation_receipts").forEach { db.execSQL("DROP TABLE $it") }
        com.example.monthlyexpense.classification.removeClassificationFromLegacyFixture(db)
        db.version = 6; helper.close()
        helper = ExpenseDatabaseHelper(context); events = NotificationEventRepository(helper); protocol = LedgerNotificationProtocol(helper)
        assertEquals(DispositionReason.LEGACY_UNKNOWN, events.dispositionReason(ignored.eventId))
        assertNull(events.eventGeneration(pending.eventId)!!.bookGeneration)
        assertNull(events.eventGeneration(pending.eventId)!!.sourceGeneration)
        events.bindLegacyGenerations(mapOf("WECHAT" to 4L), emptySet())
        assertEquals(DispositionReason.SOURCE_DISABLED, events.dispositionReason(pending.eventId))
        assertEquals(protocol.bookGeneration(), events.eventGeneration(pending.eventId)!!.bookGeneration)
        assertEquals(4L, events.eventGeneration(pending.eventId)!!.sourceGeneration)
        assertEquals(0, count())
    }

}
