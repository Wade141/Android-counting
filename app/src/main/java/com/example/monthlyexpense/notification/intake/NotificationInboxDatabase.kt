package com.example.monthlyexpense.notification.intake

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The database, journal and WAL/SHM live together outside automatic backup. */
class NotificationInboxDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, context.noBackupFilesDir.resolve(NAME).absolutePath, null, 1
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notification_inbox (
            intake_id TEXT PRIMARY KEY, process_session_id TEXT NOT NULL, sequence INTEGER NOT NULL,
            book_generation TEXT NOT NULL, source_generation INTEGER NOT NULL, source TEXT NOT NULL,
            received_at INTEGER NOT NULL, received_elapsed INTEGER NOT NULL, boot_count INTEGER,
            state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0,
            payload BLOB, nonce BLOB, payload_version INTEGER NOT NULL DEFAULT 1,
            event_id TEXT, reason TEXT, active_batch_id TEXT, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX inbox_pending ON notification_inbox(state, active_batch_id, next_attempt_at)")
        db.execSQL("CREATE INDEX inbox_session_sequence ON notification_inbox(process_session_id,sequence)")
        db.execSQL("CREATE TABLE intake_control (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE source_policies (source TEXT PRIMARY KEY, selected INTEGER NOT NULL,
            generation INTEGER NOT NULL, state TEXT NOT NULL, enabled_wall INTEGER NOT NULL,
            enabled_elapsed INTEGER NOT NULL, boot_count INTEGER, failure TEXT, boundary_payload BLOB, boundary_nonce BLOB,
            boundary_count INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE TABLE intake_batches (batch_id TEXT PRIMARY KEY, state TEXT NOT NULL, created_at INTEGER NOT NULL, not_before INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE intake_batch_members (batch_id TEXT NOT NULL REFERENCES intake_batches(batch_id),
            intake_id TEXT NOT NULL REFERENCES notification_inbox(intake_id), PRIMARY KEY(batch_id,intake_id))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    companion object { const val NAME = "notification-intake.db" }
}

internal inline fun <T> SQLiteDatabase.intakeTransaction(block: (SQLiteDatabase) -> T): T {
    beginTransaction()
    try { return block(this).also { setTransactionSuccessful() } } finally { endTransaction() }
}
