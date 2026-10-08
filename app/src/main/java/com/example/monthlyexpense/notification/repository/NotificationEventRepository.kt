package com.example.monthlyexpense.notification.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.*
import com.example.monthlyexpense.notification.decision.*
import com.example.monthlyexpense.notification.parser.*
import org.json.JSONArray
import org.json.JSONObject
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.notification.NotificationMetrics
import com.example.monthlyexpense.notification.PipelineMetric

enum class StoreResult { STAGED, INSERTED, DUPLICATE, REVIEW, IGNORED, REJECTED }
enum class DispositionReason { NONE, RULE_IGNORED, USER_IGNORED, SOURCE_DISABLED, RESTORE_INVALIDATED, LEGACY_UNKNOWN }
data class EventGeneration(val bookGeneration: String?, val sourceGeneration: Long?, val source: String)
data class QuarantineConfirmation(val result: StoreResult, val reason: DispositionReason = DispositionReason.NONE)
data class EventMutation(val result: StoreResult, val changedDomains: Set<DataDomain>)
data class StagedResult(val result: StoreResult, val shouldSchedule: Boolean)
data class StagedCursor(val eventId: String, val observedAt: Long)
data class PendingNotification(val eventId: String, val source: String, val observedAt: Long,
    val amountCents: Long?, val candidates: List<Long>, val merchant: String?, val kind: String,
    val reasons: List<String>, val ruleVersion: String)
data class LinkableExpense(val id: Long, val amountCents: Long, val name: String, val spentAt: Long)

/** All event transitions and expense writes share one SQLite transaction. No notification text is stored. */
class NotificationEventRepository(
    private val helper: ExpenseDatabaseHelper,
    private val onChanged: (Set<DataDomain>) -> Unit = {}
) {
    var onExpenseRecorded: (String) -> Unit = {}
    fun stageForScheduling(d: PaymentDecision): StagedResult = stageForScheduling(d, null, null)
    fun stageForScheduling(d: PaymentDecision, bookGeneration: String?, sourceGeneration: Long?): StagedResult {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val result = if (bookGeneration != null && bookGeneration != LedgerNotificationProtocol.currentGeneration(db))
                StoreResult.REJECTED else stageInTransaction(db, d, bookGeneration, sourceGeneration)
            StagedResult(result, result != StoreResult.REJECTED && row(db, d.eventId)?.state == "STAGED")
                .also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }
    fun stage(d: PaymentDecision): StoreResult = stageInTransaction(helper.writableDatabase, d, null, null)
    private fun stageInTransaction(db: SQLiteDatabase, d: PaymentDecision, bookGeneration: String?, sourceGeneration: Long?): StoreResult {
        if (d.source == null || d.observedAt <= 0 || d.eventId.isBlank()) return StoreResult.REJECTED
        val inserted = db.insertWithOnConflict("notification_events", null, ContentValues().apply {
            put("book_generation", bookGeneration ?: LedgerNotificationProtocol.currentGeneration(db))
            put("source_generation", sourceGeneration)
            put("disposition_reason", if (d.action == DecisionAction.IGNORE) "RULE_IGNORED" else "NONE")
            put("event_id", d.eventId); put("notification_identity", d.notificationIdentity)
            put("source", d.source.name); put("observed_at", d.observedAt); put("action", d.action.name)
            put("state", if (d.action == DecisionAction.IGNORE) "IGNORED" else "STAGED"); put("amount_cents", d.amountCents); put("merchant", d.merchant)
            put("kind", d.kind.name); put("rule_version", d.ruleVersion)
            put("reasons", JSONArray(d.reasons).toString())
            put("amounts", JSONArray().also { a -> d.amounts.forEach {
                a.put(JSONObject().put("cents", it.cents).put("role", it.role.name).put("field", it.field).put("rule", it.ruleId))
            } }.toString())
            put("legacy_event_id", d.legacyEventId); put("legacy_notification_key", d.legacyNotificationKey)
            put("transaction_ref", d.transactionRef); put("occurred_at", d.occurredAt); put("time_origin", d.timeOrigin)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (d.action == DecisionAction.IGNORE) return StoreResult.IGNORED
        return if (inserted == -1L) StoreResult.DUPLICATE else StoreResult.STAGED
    }

    fun stagedIds(): List<String> = stagedPage().map { it.eventId }

    fun eventGeneration(eventId: String): EventGeneration? = row(helper.readableDatabase, eventId)?.let {
        EventGeneration(it.bookGeneration, it.sourceGeneration, it.item.source)
    }

    fun dispositionReason(eventId: String): DispositionReason? = row(helper.readableDatabase, eventId)?.dispositionReason

    /** Called only after source policy initialization and restore reconciliation, under the ledger coordinator. */
    fun bindLegacyGenerations(sourceGenerations: Map<String, Long>, enabledSources: Set<String>) {
        val db = helper.writableDatabase
        val ownsTransaction = !db.inTransaction()
        var reviewChanged = false
        db.beginTransaction()
        try {
            val pendingRestore = db.rawQuery("SELECT 1 FROM restore_operation WHERE state = 'DB_COMMITTED' LIMIT 1", null).use { it.moveToFirst() }
            check(!pendingRestore) { "Cannot bind legacy events during restore" }
            val generation = LedgerNotificationProtocol.currentGeneration(db)
            sourceGenerations.forEach { (source, sourceGeneration) ->
                if (source !in enabledSources) {
                    reviewChanged = reviewChanged || db.rawQuery("SELECT 1 FROM notification_events WHERE source = ? AND state = 'REVIEW' AND (book_generation IS NULL OR source_generation IS NULL) LIMIT 1", arrayOf(source)).use { it.moveToFirst() }
                }
                db.update("notification_events", ContentValues().apply {
                    put("book_generation", generation); put("source_generation", sourceGeneration)
                    if (source !in enabledSources) { put("state", "IGNORED"); put("disposition_reason", "SOURCE_DISABLED") }
                }, "source = ? AND state IN ('STAGED','REVIEW') AND (book_generation IS NULL OR source_generation IS NULL)", arrayOf(source))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (ownsTransaction && reviewChanged) publishChanges(setOf(DataDomain.REVIEW))
    }

    /** Inbox ownership, source enablement, and payload verification are checked by the caller under its handoff lock. */
    fun confirmQuarantined(decision: PaymentDecision, expectedBookGeneration: String, sourceGeneration: Long,
        amountCents: Long, name: String): QuarantineConfirmation {
        var reason = DispositionReason.NONE
        val result = transaction(decision.eventId) { db ->
            if (expectedBookGeneration != LedgerNotificationProtocol.currentGeneration(db) ||
                amountCents !in 1..MoneyLimits.MAX_CENTS || name.trim().length !in 1..40) return@transaction StoreResult.REJECTED
            val confirmed = db.rawQuery("SELECT result FROM quarantine_confirmation_receipts WHERE book_generation = ? AND event_id = ?",
                arrayOf(expectedBookGeneration, decision.eventId)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (confirmed == StoreResult.IGNORED.name) {
                reason = DispositionReason.USER_IGNORED
                return@transaction StoreResult.REJECTED
            }
            if (confirmed != null) return@transaction StoreResult.DUPLICATE
            var existing = row(db, decision.eventId)
            if (existing != null && existing.dispositionReason !in setOf(DispositionReason.NONE, DispositionReason.RESTORE_INVALIDATED)) {
                reason = existing.dispositionReason
                return@transaction StoreResult.REJECTED
            }
            val exactExpense = db.rawQuery("SELECT id FROM expenses WHERE source_key IN (?, ?, ?) LIMIT 1",
                arrayOf(decision.eventId, decision.legacyEventId.orEmpty(), "legacy:${decision.legacyEventId.orEmpty()}"))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            if (existing != null && existing.state in setOf("RECORDED", "LINKED") && exactExpense == null) {
                return@transaction StoreResult.REJECTED
            }
            if (existing == null) {
                val staged = stageInTransaction(db, decision, expectedBookGeneration, sourceGeneration)
                if (staged == StoreResult.REJECTED || staged == StoreResult.IGNORED) {
                    reason = if (staged == StoreResult.IGNORED) DispositionReason.RULE_IGNORED else DispositionReason.NONE
                    return@transaction StoreResult.REJECTED
                }
                existing = row(db, decision.eventId) ?: return@transaction StoreResult.REJECTED
            }
            if (existing.dispositionReason == DispositionReason.RESTORE_INVALIDATED) {
                db.update("notification_events", ContentValues().apply {
                    put("state", "STAGED"); put("disposition_reason", "NONE")
                    put("book_generation", expectedBookGeneration); put("source_generation", sourceGeneration)
                }, "event_id = ?", arrayOf(decision.eventId))
                existing = row(db, decision.eventId) ?: return@transaction StoreResult.REJECTED
            } else if (existing.bookGeneration != expectedBookGeneration ||
                (existing.sourceGeneration != null && existing.sourceGeneration != sourceGeneration)) return@transaction StoreResult.REJECTED
            val outcome = if (exactExpense != null) {
                transition(db, decision.eventId, "RECORDED", exactExpense); StoreResult.DUPLICATE
            } else if (linkDefinitiveDuplicate(db, existing)) StoreResult.DUPLICATE
            else insert(db, existing, amountCents, name.trim())
            db.insertOrThrow("quarantine_confirmation_receipts", null, ContentValues().apply {
                put("book_generation", expectedBookGeneration); put("event_id", decision.eventId)
                put("result", outcome.name); put("completed_at", System.currentTimeMillis())
            })
            outcome
        }.result
        return QuarantineConfirmation(result, reason)
    }

    /** Persist explicit user intent before deleting the inbox payload, including aliases of this stable event. */
    fun ignoreQuarantined(decision: PaymentDecision, expectedBookGeneration: String, sourceGeneration: Long): StoreResult =
        transaction(decision.eventId) { db ->
            if (expectedBookGeneration != LedgerNotificationProtocol.currentGeneration(db)) return@transaction StoreResult.REJECTED
            val receiptExists = db.rawQuery("SELECT 1 FROM quarantine_confirmation_receipts WHERE book_generation = ? AND event_id = ?",
                arrayOf(expectedBookGeneration, decision.eventId)).use { it.moveToFirst() }
            if (receiptExists) return@transaction StoreResult.DUPLICATE
            val before = row(db, decision.eventId)
            if (before != null && (before.state in setOf("RECORDED", "LINKED") ||
                before.dispositionReason !in setOf(DispositionReason.NONE, DispositionReason.RESTORE_INVALIDATED))) {
                return@transaction StoreResult.DUPLICATE
            }
            if (before == null && stageInTransaction(db, decision, expectedBookGeneration, sourceGeneration) == StoreResult.REJECTED) {
                return@transaction StoreResult.REJECTED
            }
            // Old-book event history remains intact; the scoped receipt blocks aliases in this book.
            if (before == null || before.bookGeneration == expectedBookGeneration) {
                transition(db, decision.eventId, "IGNORED", reason = DispositionReason.USER_IGNORED)
            }
            db.insertOrThrow("quarantine_confirmation_receipts", null, ContentValues().apply {
                put("book_generation", expectedBookGeneration); put("event_id", decision.eventId)
                put("result", StoreResult.IGNORED.name); put("completed_at", System.currentTimeMillis())
            })
            StoreResult.IGNORED
        }.result

    /** Used when a previously mapped inbox payload can no longer be decrypted. */
    fun ignoreQuarantinedEvent(eventId: String, expectedBookGeneration: String): StoreResult = transaction(eventId) { db ->
        if (eventId.isBlank() || expectedBookGeneration != LedgerNotificationProtocol.currentGeneration(db)) return@transaction StoreResult.REJECTED
        val receiptExists = db.rawQuery("SELECT 1 FROM quarantine_confirmation_receipts WHERE book_generation = ? AND event_id = ?",
            arrayOf(expectedBookGeneration, eventId)).use { it.moveToFirst() }
        if (receiptExists) return@transaction StoreResult.DUPLICATE
        val existing = row(db, eventId)
        if (existing != null && (existing.state in setOf("RECORDED", "LINKED") ||
            existing.dispositionReason !in setOf(DispositionReason.NONE, DispositionReason.RESTORE_INVALIDATED))) return@transaction StoreResult.DUPLICATE
        if (existing?.bookGeneration == expectedBookGeneration) {
            transition(db, eventId, "IGNORED", reason = DispositionReason.USER_IGNORED)
        }
        db.insertOrThrow("quarantine_confirmation_receipts", null, ContentValues().apply {
            put("book_generation", expectedBookGeneration); put("event_id", eventId)
            put("result", StoreResult.IGNORED.name); put("completed_at", System.currentTimeMillis())
        })
        StoreResult.IGNORED
    }.result

    fun stagedPage(after: StagedCursor? = null): List<StagedCursor> = helper.readableDatabase.rawQuery(
        "SELECT event_id, observed_at FROM notification_events WHERE state = 'STAGED' " +
            (if (after == null) "" else "AND (observed_at > ? OR (observed_at = ? AND event_id > ?)) ") +
            "ORDER BY observed_at, event_id LIMIT 500",
        after?.let { arrayOf(it.observedAt.toString(), it.observedAt.toString(), it.eventId) }
    ).use { c -> buildList { while (c.moveToNext()) add(StagedCursor(c.getString(0), c.getLong(1))) } }

    fun process(eventId: String): StoreResult = processWithChanges(eventId).result
    fun processWithChanges(eventId: String): EventMutation = NotificationMetrics.measure(PipelineMetric.PROCESS) {
        transaction(eventId) { db -> processResultInTransaction(db, eventId) }
    }
    fun process(eventId: String, expectedBookGeneration: String, expectedSourceGeneration: Long): StoreResult =
        transaction(eventId) { db -> processResultInTransaction(db, eventId, expectedBookGeneration, expectedSourceGeneration) }.result

    internal fun processInTransaction(db: SQLiteDatabase, eventId: String,
        expectedBookGeneration: String? = null, expectedSourceGeneration: Long? = null): EventMutation {
        check(db.inTransaction())
        return mutation(db, eventId) { processResultInTransaction(db, eventId, expectedBookGeneration, expectedSourceGeneration) }
    }

    private fun processResultInTransaction(db: SQLiteDatabase, eventId: String,
        expectedBookGeneration: String? = null, expectedSourceGeneration: Long? = null): StoreResult {
        val row = row(db, eventId) ?: return StoreResult.REJECTED
        // Preserve the original terminal-result API; guarded consumers and replay use explicit generations.
        if (expectedBookGeneration == null && expectedSourceGeneration == null && row.state !in setOf("STAGED", "REVIEW")) {
            return StoreResult.DUPLICATE
        }
        val currentBook = LedgerNotificationProtocol.currentGeneration(db)
        if ((expectedBookGeneration != null && currentBook != expectedBookGeneration) ||
            (row.bookGeneration != null && row.bookGeneration != currentBook)) return StoreResult.REJECTED
        if (expectedSourceGeneration != null && row.sourceGeneration != expectedSourceGeneration) {
            if (row.state == "STAGED" || row.state == "REVIEW") {
                transition(db, eventId, "IGNORED", reason = DispositionReason.SOURCE_DISABLED)
            }
            return StoreResult.REJECTED
        }
        when (row.state) {
            "REVIEW" -> return StoreResult.REVIEW
            "STAGED" -> Unit
            else -> return if (row.dispositionReason == DispositionReason.RULE_IGNORED) StoreResult.IGNORED else StoreResult.DUPLICATE
        }
        if (linkDefinitiveDuplicate(db, row)) return StoreResult.DUPLICATE
        // Restored v3 expense rows, or an exact legacy event, already represent this transaction.
        val exactExpense = db.rawQuery("SELECT id FROM expenses WHERE source_key = ? OR source_key = ? OR source_key = ? LIMIT 1",
            arrayOf(eventId, row.legacyEventId.orEmpty(), "legacy:${row.legacyEventId.orEmpty()}")).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (exactExpense != null) { transition(db, eventId, "RECORDED", exactExpense); return StoreResult.DUPLICATE }
        if (row.action == "REVIEW") { transition(db, eventId, "REVIEW"); return StoreResult.REVIEW }
        val cents = row.item.amountCents
        if (cents == null || cents !in 1..MoneyLimits.MAX_CENTS || row.item.kind == ExpenseSubtype.TRANSFER.name) {
            transition(db, eventId, "REVIEW"); return StoreResult.REVIEW
        }
        if (possiblyDuplicate(db, row)) {
            db.update("notification_events", ContentValues().apply {
                put("state", "REVIEW"); put("reasons", JSONArray(row.item.reasons + "possible_duplicate").toString())
            }, "event_id = ?", arrayOf(eventId))
            return StoreResult.REVIEW
        }
        return insert(db, row, cents, row.item.merchant ?: label(row.item.source))
    }

    fun pending(): List<PendingNotification> = helper.readableDatabase.rawQuery(
        "SELECT * FROM notification_events WHERE state = 'REVIEW' ORDER BY observed_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(read(c).item) } }

    fun pendingCount(): Int = NotificationMetrics.measure(PipelineMetric.REVIEW_COUNT) { helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM notification_events WHERE state = 'REVIEW'", null
    ).use { it.moveToFirst(); it.getInt(0) } }

    fun pendingPage(after: PendingNotification? = null): List<PendingNotification> = NotificationMetrics.measure(PipelineMetric.REVIEW_PAGE) { helper.readableDatabase.rawQuery(
        "SELECT * FROM notification_events WHERE state = 'REVIEW' " +
            (if (after == null) "" else "AND (observed_at < ? OR (observed_at = ? AND event_id < ?)) ") +
            "ORDER BY observed_at DESC, event_id DESC LIMIT 50",
        after?.let { arrayOf(it.observedAt.toString(), it.observedAt.toString(), it.eventId) }
    ).use { c -> buildList { while (c.moveToNext()) add(read(c).item) } } }

    fun confirm(eventId: String, amountCents: Long, name: String): StoreResult = confirmWithChanges(eventId, amountCents, name).result
    fun confirmWithChanges(eventId: String, amountCents: Long, name: String): EventMutation = transaction(eventId) { db ->
        val row = row(db, eventId) ?: return@transaction StoreResult.REJECTED
        if (row.state != "REVIEW") return@transaction StoreResult.DUPLICATE
        if (amountCents !in 1..MoneyLimits.MAX_CENTS || name.trim().length !in 1..40) return@transaction StoreResult.REJECTED
        if (linkDefinitiveDuplicate(db, row)) return@transaction StoreResult.DUPLICATE
        insert(db, row, amountCents, name.trim())
    }

    fun ignore(eventId: String): StoreResult = ignoreWithChanges(eventId).result
    fun ignoreWithChanges(eventId: String): EventMutation = transaction(eventId) { db ->
        val row = row(db, eventId) ?: return@transaction StoreResult.REJECTED
        if (row.state != "REVIEW") return@transaction StoreResult.DUPLICATE
        transition(db, eventId, "IGNORED", reason = DispositionReason.USER_IGNORED); StoreResult.IGNORED
    }

    fun link(eventId: String, expenseId: Long): StoreResult = linkWithChanges(eventId, expenseId).result
    fun linkWithChanges(eventId: String, expenseId: Long): EventMutation = transaction(eventId) { db ->
        val row = row(db, eventId) ?: return@transaction StoreResult.REJECTED
        if (row.state != "REVIEW") return@transaction StoreResult.DUPLICATE
        val exists = db.rawQuery("SELECT id FROM expenses WHERE id = ?", arrayOf(expenseId.toString())).use { it.moveToFirst() }
        if (!exists) return@transaction StoreResult.REJECTED
        transition(db, eventId, "LINKED", expenseId); StoreResult.DUPLICATE
    }

    fun linkCandidates(eventId: String): List<LinkableExpense> {
        val r = row(helper.readableDatabase, eventId) ?: return emptyList()
        return helper.readableDatabase.rawQuery(
            "SELECT id, amount_cents, name, spent_at FROM expenses WHERE spent_at BETWEEN ? AND ? ORDER BY ABS(spent_at - ?) LIMIT 20",
            arrayOf((r.item.observedAt - 86400000L).toString(), (r.item.observedAt + 86400000L).toString(), r.item.observedAt.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(LinkableExpense(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))) } }
    }

    private fun linkDefinitiveDuplicate(db: SQLiteDatabase, row: EventRow): Boolean {
        // Engine-ignored pending/failure snapshots are not completed transactions. User ignores are.
        val terminal = db.rawQuery("""SELECT expense_id FROM notification_events WHERE event_id != ?
            AND state IN ('RECORDED','LINKED','IGNORED') AND action != 'IGNORE' AND (
            (transaction_ref IS NOT NULL AND transaction_ref = ?) OR
            (legacy_event_id IS NOT NULL AND legacy_event_id = ? AND (event_id LIKE 'legacy:%' OR ? LIKE 'legacy:%'))
            ) LIMIT 1""", arrayOf(row.item.eventId, row.transactionRef.orEmpty(), row.legacyEventId.orEmpty(), row.item.eventId))
            .use { c -> if (c.moveToFirst()) (if (c.isNull(0)) -1L else c.getLong(0)) else null }
        if (terminal == null) return false
        transition(db, row.item.eventId, "LINKED", terminal.takeIf { it > 0 })
        return true
    }

    private fun insert(db: SQLiteDatabase, row: EventRow, cents: Long, name: String): StoreResult {
        val inserted = helper.expenseDao.insertExpenseRecord(db, cents, BuiltInCategoryKeys.OTHER, name, "",
            row.occurredAt, source(row.item.source), row.item.merchant, row.item.eventId)
        val id = db.rawQuery("SELECT id FROM expenses WHERE source_key = ?", arrayOf(row.item.eventId))
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: error("Expense insertion failed")
        transition(db, row.item.eventId, "RECORDED", id)
        return if (inserted) StoreResult.INSERTED else StoreResult.DUPLICATE
    }

    private fun possiblyDuplicate(db: SQLiteDatabase, row: EventRow): Boolean {
        val item = row.item
        val start = (item.observedAt - 3000).toString(); val end = (item.observedAt + 3000).toString()
        val previousEvent = db.rawQuery("""SELECT event_id FROM notification_events WHERE event_id != ?
            AND state IN ('RECORDED','LINKED','IGNORED') AND source = ? AND amount_cents = ?
            AND (notification_identity = ? OR (observed_at BETWEEN ? AND ? AND merchant IS NOT NULL AND merchant = ?))
            AND (transaction_ref IS NULL OR ? = '' OR transaction_ref = ?) LIMIT 1""",
            arrayOf(item.eventId, item.source, item.amountCents.toString(), row.identity, start, end, item.merchant.orEmpty(), row.transactionRef.orEmpty(), row.transactionRef.orEmpty())).use { it.moveToFirst() }
        if (previousEvent) return true
        return db.rawQuery("""SELECT id FROM expenses WHERE source = ? AND amount_cents = ? AND spent_at BETWEEN ? AND ?
            AND ((merchant IS NOT NULL AND merchant = ?) OR source_key = ? OR source_key LIKE ?)
            AND NOT EXISTS (SELECT 1 FROM notification_events e WHERE e.expense_id = expenses.id AND e.transaction_ref IS NOT NULL AND ? != '' AND e.transaction_ref != ?) LIMIT 1""",
            arrayOf(source(item.source).name, item.amountCents.toString(), start, end, item.merchant.orEmpty(),
                row.legacyKey.orEmpty(), row.legacyEventId?.substringBeforeLast(':')?.plus(":%") ?: "no-legacy-match", row.transactionRef.orEmpty(), row.transactionRef.orEmpty())
        ).use { it.moveToFirst() }
    }

    private fun transition(db: SQLiteDatabase, id: String, state: String, expenseId: Long? = null,
        reason: DispositionReason? = null) {
        db.update("notification_events", ContentValues().apply {
            put("state", state); put("expense_id", expenseId)
            if (reason != null) put("disposition_reason", reason.name)
        }, "event_id = ?", arrayOf(id))
    }
    private fun row(db: SQLiteDatabase, id: String): EventRow? = db.rawQuery("SELECT * FROM notification_events WHERE event_id = ?", arrayOf(id))
        .use { if (it.moveToFirst()) read(it) else null }
    private fun read(c: Cursor): EventRow {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun optional(key: String): String? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
        val amounts = JSONArray(text("amounts"))
        val reasons = JSONArray(text("reasons"))
        return EventRow(PendingNotification(text("event_id"), text("source"), c.getLong(c.getColumnIndexOrThrow("observed_at")),
            c.getColumnIndexOrThrow("amount_cents").let { if (c.isNull(it)) null else c.getLong(it) },
            (0 until amounts.length()).map { amounts.getJSONObject(it).getLong("cents") }.distinct(),
            optional("merchant"), text("kind"), (0 until reasons.length()).map(reasons::getString), text("rule_version")),
            text("state"), text("action"), text("notification_identity"), optional("legacy_event_id"), optional("legacy_notification_key"),
            optional("transaction_ref"), c.getLong(c.getColumnIndexOrThrow("occurred_at")),
            optional("book_generation"), optional("source_generation")?.toLong(),
            DispositionReason.valueOf(text("disposition_reason")))
    }
    private fun mutation(db: SQLiteDatabase, eventId: String, block: () -> StoreResult): EventMutation {
        val before = row(db, eventId)?.state
        val result = block()
        val after = row(db, eventId)?.state
        return EventMutation(result, buildSet {
            if (result == StoreResult.INSERTED) add(DataDomain.LEDGER)
            if (before != after && (before == "REVIEW" || after == "REVIEW")) add(DataDomain.REVIEW)
        })
    }
    private fun transaction(eventId: String, block: (SQLiteDatabase) -> StoreResult): EventMutation {
        val db = helper.writableDatabase
        val ownsTransaction = !db.inTransaction()
        db.beginTransaction()
        val outcome = try {
            mutation(db, eventId) { block(db) }.also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
        // External transaction owners publish accumulated changes after their own commit.
        if (ownsTransaction) {
            if (outcome.result == StoreResult.INSERTED) publishRecorded(eventId)
            publishChanges(outcome.changedDomains)
        }
        return outcome
    }
    internal fun publishChanges(domains: Set<DataDomain>) { if (domains.isNotEmpty()) onChanged(domains) }
    internal fun publishRecorded(eventId: String) {
        try { onExpenseRecorded(eventId) } catch (_: RuntimeException) { /* Ledger is already committed. */ }
    }
    private fun source(source: String) = if (source == "WECHAT") ExpenseSource.WECHAT_AUTO else ExpenseSource.ALIPAY_AUTO
    private fun label(source: String) = if (source == "WECHAT") "微信自动记账" else "支付宝自动记账"
    private data class EventRow(val item: PendingNotification, val state: String, val action: String,
        val identity: String, val legacyEventId: String?, val legacyKey: String?, val transactionRef: String?, val occurredAt: Long,
        val bookGeneration: String?, val sourceGeneration: Long?, val dispositionReason: DispositionReason)
}
