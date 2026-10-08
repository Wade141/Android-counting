package com.example.monthlyexpense.notification.intake

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.PaymentSource
import java.util.UUID

class NotificationInboxRepository(val database: NotificationInboxDatabase, private val cipher: PayloadCipher) {
    fun persist(raw: RawNotification, bookGeneration: String, sourceGeneration: Long, sequence: Long,
        receivedAt: Long = System.currentTimeMillis(), receivedElapsed: Long = android.os.SystemClock.elapsedRealtime(),
        bootCount: Int? = null, processSessionId: String = "legacy", clock: IntakeClock = IntakeClock(receivedAt, receivedElapsed, bootCount)
    ): IntakePersistResult {
        val source = paymentSource(raw.packageName) ?: return IntakePersistResult.Rejected(IntakeFailure.UNSUPPORTED_SOURCE)
        val plaintext = try { NotificationPayloadCodec.encode(raw) } catch (_: IllegalArgumentException) {
            return IntakePersistResult.Rejected(IntakeFailure.TOO_LARGE)
        }
        val id = UUID.randomUUID().toString()
        val encrypted = try { cipher.encrypt(plaintext, identity(id, bookGeneration, sourceGeneration)) } catch (_: Exception) {
            return IntakePersistResult.Rejected(IntakeFailure.ENCRYPTION)
        } finally { plaintext.fill(0) }
        return try {
            database.writableDatabase.intakeTransaction { db ->
                expire(db, clock.wallTime)
                val policy = NotificationSourceGeneration.readPolicy(db, source)
                val restoreBarrier = control(db,"restore_barrier")
                val currentBook = control(db,"book_generation")
                val bookChanged = currentBook != null && currentBook != bookGeneration
                // A pre-initialization callback carries generation zero and can only be isolated.
                // Do not lose it merely because the policy import won the race to this transaction.
                if (sourceGeneration != 0L && policy != null && policy.generation != sourceGeneration && restoreBarrier == null && !bookChanged) return@intakeTransaction IntakePersistResult.Rejected(IntakeFailure.SOURCE_CHANGED)
                if (sourceGeneration != 0L && policy?.state == SourcePolicyState.OFF && restoreBarrier == null) return@intakeTransaction IntakePersistResult.Rejected(IntakeFailure.SOURCE_DISABLED)
                val usage = payloadUsage(db)
                if (usage.first >= MAX_ITEMS || usage.second + encrypted.ciphertext.size + encrypted.nonce.size > MAX_TOTAL_BYTES)
                    return@intakeTransaction IntakePersistResult.Rejected(IntakeFailure.CAPACITY)
                val reason = when {
                    restoreBarrier != null -> QuarantineReason.BOOK_RESTORE
                    bookChanged -> QuarantineReason.BOOK_CHANGED
                    sourceGeneration == 0L || policy == null || control(db, "policies_initialized") != "1" -> QuarantineReason.INITIALIZING
                    policy.state == SourcePolicyState.ARMING -> QuarantineReason.ENABLE_BOUNDARY
                    else -> NotificationSourceGeneration.classify(db, cipher, policy, raw, clock)
                }
                db.insertOrThrow("notification_inbox", null, ContentValues().apply {
                    put("intake_id", id); put("process_session_id", processSessionId); put("sequence", sequence)
                    put("book_generation", bookGeneration); put("source_generation", sourceGeneration); put("source", source.name)
                    put("received_at", clock.wallTime); put("received_elapsed", clock.elapsedTime); put("boot_count", clock.bootCount)
                    put("state", if (reason == null) IntakeState.PENDING.name else IntakeState.QUARANTINED.name)
                    put("payload", encrypted.ciphertext); put("nonce", encrypted.nonce); put("reason", reason?.name)
                    put("updated_at", clock.wallTime)
                })
                IntakePersistResult.Saved(id)
            }
        } catch (_: Exception) { IntakePersistResult.Rejected(IntakeFailure.STORAGE) }
    }

    fun find(intakeId: String): IntakeRecord? = database.readableDatabase.rawQuery(
        "SELECT * FROM notification_inbox WHERE intake_id=?", arrayOf(intakeId)).use { if (it.moveToFirst()) record(it) else null }

    fun readPayload(intakeId: String, now: Long = System.currentTimeMillis()): RawNotification? {
        val item = find(intakeId) ?: return null
        if (now - item.receivedAt >= RETENTION_MS) { terminal(intakeId, IntakeState.EXPIRED, now); return null }
        if (now < item.receivedAt - 2_000) { quarantine(intakeId, QuarantineReason.CLOCK_UNCERTAIN); return null }
        val encrypted = database.readableDatabase.rawQuery("SELECT payload,nonce FROM notification_inbox WHERE intake_id=?", arrayOf(intakeId)).use {
            if (!it.moveToFirst() || it.isNull(0)) null else EncryptedPayload(it.getBlob(0), it.getBlob(1))
        } ?: return null
        val bytes = try { cipher.decrypt(encrypted, identity(item.intakeId, item.bookGeneration, item.sourceGeneration)) }
        catch (_: Exception) { quarantine(intakeId, QuarantineReason.KEY_UNAVAILABLE); return null }
        return try { NotificationPayloadCodec.decode(bytes) }
        catch (_: Exception) { quarantine(intakeId, QuarantineReason.PAYLOAD_INVALID); null }
        finally { bytes.fill(0) }
    }

    /** Caller holds the application handoff mutex and ledger mutation coordinator. */
    fun claim(intakeId: String, now: Long = System.currentTimeMillis()): IntakeRecord? = database.writableDatabase.intakeTransaction { db ->
        expire(db, now)
        if (control(db, "restore_barrier") != null) return@intakeTransaction null
        val item = find(intakeId) ?: return@intakeTransaction null
        val policy = NotificationSourceGeneration.readPolicy(db, item.source)
        if (policy == null || policy.state != SourcePolicyState.ON || policy.generation != item.sourceGeneration) return@intakeTransaction null
        if (item.state != IntakeState.PENDING || item.nextAttemptAt > now || item.attempts >= MAX_ATTEMPTS) return@intakeTransaction null
        if (now < item.receivedAt - 2_000) { quarantine(intakeId, QuarantineReason.CLOCK_UNCERTAIN); return@intakeTransaction null }
        db.execSQL("UPDATE notification_inbox SET state='PROCESSING', attempts=attempts+1, updated_at=? WHERE intake_id=?", arrayOf(now, intakeId))
        find(intakeId)
    }
    fun acknowledgeHandoff(intakeId: String, eventId: String) {
        database.writableDatabase.update("notification_inbox", ContentValues().apply {
            put("state", IntakeState.HANDED_OFF.name); put("event_id", eventId); putNull("payload"); putNull("nonce")
            putNull("reason"); put("updated_at", System.currentTimeMillis())
        }, "intake_id=? AND state IN ('PENDING','PROCESSING','QUARANTINED')", arrayOf(intakeId))
    }
    fun quarantine(intakeId: String, reason: QuarantineReason) {
        database.writableDatabase.update("notification_inbox", ContentValues().apply {
            put("state", IntakeState.QUARANTINED.name); put("reason", reason.name); put("updated_at", System.currentTimeMillis())
        }, "intake_id=? AND state NOT IN ('HANDED_OFF','DISCARDED','EXPIRED')", arrayOf(intakeId))
    }
    fun discard(intakeId: String) = terminal(intakeId, IntakeState.DISCARDED, System.currentTimeMillis())
    private fun terminal(id: String, state: IntakeState, now: Long) {
        database.writableDatabase.update("notification_inbox", ContentValues().apply {
            put("state", state.name); putNull("payload"); putNull("nonce"); put("updated_at", now)
        }, "intake_id=? AND state!='HANDED_OFF'", arrayOf(id))
    }
    fun retry(intakeId: String, now: Long = System.currentTimeMillis()) = database.writableDatabase.intakeTransaction { db ->
        val item = find(intakeId) ?: return@intakeTransaction
        if (item.state != IntakeState.PROCESSING) return@intakeTransaction
        db.update("notification_inbox", ContentValues().apply {
            put("state", if (item.attempts >= MAX_ATTEMPTS) IntakeState.FAILED.name else IntakeState.PENDING.name)
            put("next_attempt_at", now + if (item.attempts <= 1) 10_000 else 60_000); put("updated_at", now)
        }, "intake_id=?", arrayOf(intakeId))
    }
    /** Only called during startup, before consumers are released. Reserved attempts remain spent. */
    fun recoverProcessing(now: Long = System.currentTimeMillis()) = database.writableDatabase.intakeTransaction { db ->
        db.execSQL("""UPDATE notification_inbox SET state=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END,
            next_attempt_at=MAX(next_attempt_at, ? + CASE WHEN attempts<=1 THEN 10000 ELSE 60000 END), updated_at=? WHERE state='PROCESSING'""", arrayOf(now, now))
        expire(db, now)
    }
    fun pendingIds(): List<String> = records("state IN ('PENDING','PROCESSING')").map { it.intakeId }
    fun listQuarantined(): List<IntakeRecord> = records("state='QUARANTINED'")
    fun listUpTo(processSessionId: String, sequence: Long): List<IntakeRecord> = records("process_session_id=? AND sequence<=?", arrayOf(processSessionId, sequence.toString()))
    fun records(where: String, args: Array<String> = emptyArray()): List<IntakeRecord> = database.readableDatabase.rawQuery(
        "SELECT * FROM notification_inbox WHERE $where ORDER BY received_at,intake_id", args).use { cursor -> buildList { while(cursor.moveToNext()) add(record(cursor)) } }
    fun restoreBarrierId(): String? = control(database.readableDatabase, "restore_barrier")
    fun setRestoreBarrier(id: String?) = database.writableDatabase.intakeTransaction { db -> setControl(db, "restore_barrier", id) }
    fun setBookGeneration(generation: String) = database.writableDatabase.intakeTransaction { db -> setControl(db,"book_generation",generation) }
    fun failureCount(): Long = control(database.readableDatabase,"intake_failure_count")?.toLongOrNull() ?: 0
    /** Best effort only: full or inaccessible storage can prevent even this aggregate from saving. */
    fun recordFailure(reason: IntakeFailure, count: Long = 1) {
        if(count <= 0) return
        runCatching { database.writableDatabase.intakeTransaction { db ->
            val previous = control(db,"intake_failure_count")?.toLongOrNull() ?: 0
            setControl(db,"intake_failure_count",(previous+count).toString())
            setControl(db,"intake_failure_class",reason.name)
        } }
    }

    /** Membership is appended only here, in the same transaction as ownership; never edited later. */
    fun createBatch(now: Long = System.currentTimeMillis()): IntakeBatch? = database.writableDatabase.intakeTransaction { db ->
        expire(db, now)
        if(control(db,"restore_barrier") != null) return@intakeTransaction null
        val eligible = "state='PENDING' AND active_batch_id IS NULL AND EXISTS (SELECT 1 FROM source_policies p WHERE p.source=notification_inbox.source AND p.generation=notification_inbox.source_generation AND p.state='ON')"
        val next = db.rawQuery("SELECT MIN(next_attempt_at) FROM notification_inbox WHERE $eligible", null).use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getLong(0)
        } ?: return@intakeTransaction null
        val due = if (next <= now) now else next
        val clause = if (next <= now) "next_attempt_at<=?" else "next_attempt_at=?"
        val ids = db.rawQuery("SELECT intake_id FROM notification_inbox WHERE $eligible AND $clause ORDER BY received_at,intake_id LIMIT 50", arrayOf(due.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        if (ids.isEmpty()) return@intakeTransaction null
        val batch = IntakeBatch(UUID.randomUUID().toString(), "READY", now, if (next <= now) 0 else next)
        db.execSQL("INSERT INTO intake_batches(batch_id,state,created_at,not_before) VALUES(?,?,?,?)", arrayOf(batch.batchId,batch.state,now,batch.notBefore))
        ids.forEach { id ->
            db.execSQL("INSERT INTO intake_batch_members(batch_id,intake_id) VALUES(?,?)", arrayOf(batch.batchId,id))
            db.execSQL("UPDATE notification_inbox SET active_batch_id=? WHERE intake_id=?", arrayOf(batch.batchId,id))
        }
        batch
    }
    fun unfinishedBatches(): List<IntakeBatch> = database.readableDatabase.rawQuery("SELECT batch_id,state,created_at,not_before FROM intake_batches WHERE state!='DONE' ORDER BY created_at,batch_id", null).use { c -> buildList { while(c.moveToNext()) add(IntakeBatch(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3))) } }
    fun batchMembers(batchId: String): List<IntakeRecord> = records("intake_id IN (SELECT intake_id FROM intake_batch_members WHERE batch_id=?)", arrayOf(batchId))
    fun markBatchEnqueued(batchId: String) { database.writableDatabase.execSQL("UPDATE intake_batches SET state='ENQUEUED' WHERE batch_id=? AND state='READY'", arrayOf(batchId)) }
    fun finishBatch(batchId: String) = database.writableDatabase.intakeTransaction { db ->
        db.execSQL("UPDATE intake_batches SET state='DONE' WHERE batch_id=?", arrayOf(batchId))
        db.execSQL("UPDATE notification_inbox SET active_batch_id=NULL WHERE active_batch_id=?", arrayOf(batchId))
    }
    fun maintenance(now: Long = System.currentTimeMillis()) = database.writableDatabase.intakeTransaction { db ->
        expire(db, now)
        // Keep mappings for replay references; pruning requires the replay owner to release them.
        db.execSQL("DELETE FROM intake_batch_members WHERE batch_id IN (SELECT batch_id FROM intake_batches WHERE state='DONE' AND created_at<?)", arrayOf(now - TERMINAL_RETENTION_MS))
        db.execSQL("DELETE FROM intake_batches WHERE state='DONE' AND created_at<?", arrayOf(now - TERMINAL_RETENTION_MS))
    }
    /** The replay owner supplies every still-referenced id before pruning terminal mappings. */
    fun pruneTerminalMappings(referencedIds: Set<String>, now: Long = System.currentTimeMillis()) = database.writableDatabase.intakeTransaction { db ->
        val candidates = records("state IN ('HANDED_OFF','EXPIRED','DISCARDED') AND updated_at<? AND active_batch_id IS NULL", arrayOf((now-TERMINAL_RETENTION_MS).toString()))
        candidates.filter { it.intakeId !in referencedIds }.forEach { item ->
            db.delete("intake_batch_members","intake_id=? AND batch_id IN (SELECT batch_id FROM intake_batches WHERE state='DONE')",arrayOf(item.intakeId))
            db.delete("notification_inbox","intake_id=? AND NOT EXISTS (SELECT 1 FROM intake_batch_members WHERE intake_id=?)",arrayOf(item.intakeId,item.intakeId))
        }
    }

    companion object {
        const val MAX_ITEMS = 500
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        const val MAX_ATTEMPTS = 3
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val TERMINAL_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private fun identity(id: String, book: String, generation: Long) = "intake:1:$id:$book:$generation"
        internal fun control(db: SQLiteDatabase, key: String): String? = db.rawQuery("SELECT value FROM intake_control WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
        internal fun setControl(db: SQLiteDatabase, key: String, value: String?) {
            if (value == null) db.delete("intake_control", "key=?", arrayOf(key))
            else db.execSQL("INSERT OR REPLACE INTO intake_control(key,value) VALUES(?,?)", arrayOf(key,value))
        }
        internal fun payloadUsage(db: SQLiteDatabase): Pair<Int, Long> {
            val rows = db.rawQuery("SELECT count(*),COALESCE(sum(length(payload)+length(nonce)),0) FROM notification_inbox WHERE payload IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
            val boundary = db.rawQuery("SELECT COALESCE(sum(boundary_count),0),COALESCE(sum(length(boundary_payload)+length(boundary_nonce)),0) FROM source_policies WHERE boundary_payload IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
            return rows.first+boundary.first to rows.second+boundary.second
        }
        internal fun expire(db: SQLiteDatabase, now: Long) {
            db.execSQL("UPDATE notification_inbox SET state='EXPIRED',payload=NULL,nonce=NULL,updated_at=? WHERE payload IS NOT NULL AND received_at<=?", arrayOf(now, now-RETENTION_MS))
        }
        private fun record(c: Cursor): IntakeRecord {
            fun string(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
            val bootIndex = c.getColumnIndexOrThrow("boot_count")
            return IntakeRecord(string("intake_id"),string("process_session_id"),long("sequence"),string("book_generation"),long("source_generation"),PaymentSource.valueOf(string("source")),long("received_at"),long("received_elapsed"),if(c.isNull(bootIndex)) null else c.getInt(bootIndex),IntakeState.valueOf(string("state")),long("attempts").toInt(),long("next_attempt_at"),string("event_id"),string("reason")?.let { runCatching { QuarantineReason.valueOf(it) }.getOrNull() })
        }
    }
}
