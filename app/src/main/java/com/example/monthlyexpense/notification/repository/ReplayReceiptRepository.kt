package com.example.monthlyexpense.notification.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.data.DataDomain

data class ReplayItemReceipt(val operationId: String, val itemId: String, val eventId: String,
    val bookGeneration: String, val result: StoreResult, val completedAt: Long)

/** The receipt is the source of truth for attribution, including after a process restart. */
class ReplayReceiptRepository(private val helper: ExpenseDatabaseHelper,
    private val events: NotificationEventRepository) {
    fun processForReplay(operationId: String, itemId: String, eventId: String,
        expectedBookGeneration: String? = null, expectedSourceGeneration: Long? = null): ReplayItemReceipt {
        require(operationId.isNotBlank() && itemId.isNotBlank() && eventId.isNotBlank())
        val db = helper.writableDatabase
        val ownsTransaction = !db.inTransaction()
        var changes: Set<DataDomain> = emptySet()
        db.beginTransaction()
        val receipt = try {
            val generation = LedgerNotificationProtocol.currentGeneration(db)
            check(expectedBookGeneration == null || expectedBookGeneration == generation) { "Replay book generation changed" }
            val existing = find(db, operationId, eventId) ?: db.rawQuery(
                "SELECT * FROM replay_item_receipts WHERE operation_id = ? AND item_id = ?", arrayOf(operationId, itemId)
            ).use { if (it.moveToFirst()) read(it) else null }
            if (existing != null) {
                check(existing.bookGeneration == generation && existing.eventId == eventId) { "Replay receipt scope changed" }
                existing
            } else {
                val mutation = events.processInTransaction(db, eventId, generation, expectedSourceGeneration)
                changes = mutation.changedDomains
                ReplayItemReceipt(operationId, itemId, eventId, generation, mutation.result, System.currentTimeMillis()).also {
                    db.insertOrThrow("replay_item_receipts", null, ContentValues().apply {
                        put("operation_id", it.operationId); put("item_id", it.itemId); put("event_id", it.eventId)
                        put("book_generation", it.bookGeneration); put("result", it.result.name); put("completed_at", it.completedAt)
                    })
                }
            }.also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
        if (ownsTransaction) {
            if (DataDomain.LEDGER in changes) events.publishRecorded(eventId)
            events.publishChanges(changes)
        }
        return receipt
    }

    fun receipts(operationId: String): List<ReplayItemReceipt> = helper.readableDatabase.rawQuery(
        "SELECT * FROM replay_item_receipts WHERE operation_id = ? ORDER BY completed_at, item_id", arrayOf(operationId)
    ).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    /** Only operation IDs returned by the operation store's terminal cleanup queue are passed here. */
    fun deleteOperations(operationIds: List<String>) {
        if (operationIds.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            operationIds.distinct().forEach { db.delete("replay_item_receipts", "operation_id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun find(operationId: String, eventId: String): ReplayItemReceipt? = find(helper.readableDatabase, operationId, eventId)
    private fun find(db: SQLiteDatabase, operationId: String, eventId: String): ReplayItemReceipt? = db.rawQuery(
        "SELECT * FROM replay_item_receipts WHERE operation_id = ? AND event_id = ?", arrayOf(operationId, eventId)
    ).use { if (it.moveToFirst()) read(it) else null }
    private fun read(c: Cursor) = ReplayItemReceipt(
        c.getString(c.getColumnIndexOrThrow("operation_id")), c.getString(c.getColumnIndexOrThrow("item_id")),
        c.getString(c.getColumnIndexOrThrow("event_id")), c.getString(c.getColumnIndexOrThrow("book_generation")),
        StoreResult.valueOf(c.getString(c.getColumnIndexOrThrow("result"))), c.getLong(c.getColumnIndexOrThrow("completed_at")))
}
