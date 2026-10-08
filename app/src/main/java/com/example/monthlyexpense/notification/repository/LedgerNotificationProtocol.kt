package com.example.monthlyexpense.notification.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.ExpenseDatabaseHelper

data class LedgerRestoreOperation(val restoreId: String, val bookGeneration: String,
    val targetSettingsJson: String, val state: String)

/** Main-database half of the restore handshake. Settings and inbox barriers live outside its transactions. */
class LedgerNotificationProtocol(private val helper: ExpenseDatabaseHelper) {
    fun bookGeneration(): String = currentGeneration(helper.readableDatabase)
    fun latestRestore(): LedgerRestoreOperation? = helper.readableDatabase.rawQuery(
        "SELECT restore_id, book_generation, target_settings_json, state FROM restore_operation ORDER BY rowid DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) LedgerRestoreOperation(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) else null }
    fun pendingRestore(): LedgerRestoreOperation? = latestRestore()?.takeIf { it.state == "DB_COMMITTED" }

    fun markRestoreCommitted(db: SQLiteDatabase, restoreId: String, newBookGeneration: String, targetSettingsJson: String) {
        check(db.inTransaction()) { "Restore marker must commit with the replacement ledger" }
        require(restoreId.isNotBlank() && newBookGeneration.isNotBlank())
        check(newBookGeneration != currentGeneration(db)) { "A replacement ledger needs a new generation" }
        db.execSQL("UPDATE notification_events SET state = 'IGNORED', disposition_reason = 'RESTORE_INVALIDATED', expense_id = NULL WHERE state IN ('STAGED','REVIEW')")
        db.execSQL("UPDATE notification_events SET expense_id = NULL")
        db.execSQL("UPDATE ledger_metadata SET book_generation = ? WHERE singleton = 1", arrayOf(newBookGeneration))
        db.insertOrThrow("restore_operation", null, ContentValues().apply {
            put("restore_id", restoreId); put("book_generation", newBookGeneration)
            put("target_settings_json", targetSettingsJson); put("state", "DB_COMMITTED")
            put("created_at", System.currentTimeMillis())
        })
    }

    fun completeRestore(restoreId: String): Boolean = helper.writableDatabase.update("restore_operation",
        ContentValues().apply { put("state", "COMPLETED") },
        "restore_id = ? AND book_generation = (SELECT book_generation FROM ledger_metadata WHERE singleton = 1)", arrayOf(restoreId)) == 1

    companion object {
        fun currentGeneration(db: SQLiteDatabase): String = db.rawQuery(
            "SELECT book_generation FROM ledger_metadata WHERE singleton = 1", null
        ).use { check(it.moveToFirst()) { "Missing book generation" }; it.getString(0) }
    }
}
