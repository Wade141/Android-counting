package com.example.monthlyexpense.notification.replay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.provider.Settings
import com.example.monthlyexpense.notification.ReplayBudget
import com.example.monthlyexpense.notification.ReplayBudgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Independent of the ledger restore lock. Call writes on a background dispatcher.
 * The document is metadata only; receipt results are replaceable caches of ledger facts.
 */
class ReplayOperationStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val bootCount: () -> Int? = {
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
    },
    private val processSessionId: String = PROCESS_SESSION
) : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext,
        File(context.noBackupFilesDir, "notification-replay.db").absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE replay_operations (operation_id TEXT PRIMARY KEY, manual INTEGER NOT NULL, created_at INTEGER NOT NULL, document TEXT NOT NULL)")
            db.execSQL("CREATE TABLE replay_cleanup (operation_id TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    fun get(operationId: String): ReplayOperation? = read(helper.readableDatabase, operationId)
    fun latestManual(): ReplayOperation? = helper.readableDatabase.rawQuery(
        "SELECT document FROM replay_operations WHERE manual=1 ORDER BY created_at DESC, rowid DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) decode(it.getString(0)) else null }
    fun observe(operationId: String): Flow<ReplayOperation> = changes.map { get(operationId) }
        .filterNotNull().distinctUntilChanged().flowOn(Dispatchers.IO)
    fun observeLatestManual(): Flow<ReplayOperation?> = changes.map { latestManual() }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    fun create(operationId: String = UUID.randomUUID().toString(), manual: Boolean, bookGeneration: String): ReplayOperation = transaction { db ->
        read(db, operationId)?.also {
            require(it.manual == manual && it.bookGeneration == bookGeneration)
        } ?: ReplayOperation(operationId, manual, now(), bookGeneration).also { next ->
            if (manual) all(db).filter { it.manual && !it.superseded }.forEach { previous ->
                write(db, if (previous.phase.terminal) previous.copy(superseded = true) else previous.copy(
                    superseded = true, phase = ReplayPhase.INTERRUPTED,
                    reason = ReplayTerminationReason.INTERRUPTED, finishedAt = now()))
            }
            write(db, next)
        }
    }

    /** Resuming a Worker keeps its original attempt. Only a user's explicit retry resets budgets. */
    fun beginAttempt(operationId: String, userRetry: Boolean = false): ReplayAttempt? = transaction { db ->
        val op = requireOperation(db, operationId)
        if (op.reason == ReplayTerminationReason.BOOK_CHANGED || op.superseded) return@transaction null
        if (!userRetry && op.attempt != null) return@transaction if (op.phase.terminal) null else op.attempt
        if (userRetry && !op.canRetry) return@transaction null
        if (op.phase == ReplayPhase.COMPLETED) return@transaction null
        val attempt = ReplayAttempt(UUID.randomUUID().toString(), now(), elapsed(), bootCount(), now() + ATTEMPT_DURATION,
            processSessionId = processSessionId)
        write(db, op.copy(attempts = op.attempts + attempt, phase = ReplayPhase.QUEUED,
            reason = null, dismissed = false, finishedAt = null,
            collected = if (op.collectionSealed) op.collected else emptySet(),
            fenceSessionId = if (op.collectionSealed) op.fenceSessionId else null,
            fenceSequence = if (op.collectionSealed) op.fenceSequence else null))
        attempt
    }

    fun remainingMillis(operationId: String, attemptId: String): Long {
        val op = get(operationId) ?: return 0
        if (op.phase.terminal || op.superseded) return 0
        val attempt = op.attempt?.takeIf { it.attemptId == attemptId } ?: return 0
        return remaining(attempt)
    }

    private fun remaining(attempt: ReplayAttempt): Long {
        val wallAge = now() - attempt.createdAt
        val elapsedAge = elapsed() - attempt.createdElapsed
        val boot = bootCount()
        val sameBoot = if (boot != null) attempt.bootCount == boot else
            attempt.bootCount == null && attempt.processSessionId == processSessionId
        if (!sameBoot || wallAge < 0 || elapsedAge < 0) return 0
        return minOf(attempt.deadlineAt - now(), ATTEMPT_DURATION - elapsedAge).coerceAtLeast(0)
    }

    fun budget(operationId: String, attemptId: String): ReplayBudget? {
        val op = get(operationId) ?: return null
        val attempt = op.attempt?.takeIf { it.attemptId == attemptId } ?: return null
        if (remainingMillis(operationId, attemptId) <= 0) return null
        return ReplayBudget(attempt.budget) { next ->
            runCatching {
                transaction { db ->
                    val current = requireOperation(db, operationId)
                    val active = current.attempt ?: return@transaction false
                    if (active.attemptId != attemptId || current.phase.terminal || current.superseded || remaining(active) <= 0) return@transaction false
                    // A stale budget object must not refund another executor's reservation.
                    val prior = active.budget
                    val read = next.reads == prior.reads + 1 && next.recoveryUsed == prior.recoveryUsed &&
                        next.readsAfterRecovery == prior.readsAfterRecovery + if (prior.recoveryUsed) 1 else 0
                    val recover = !prior.recoveryUsed && next.recoveryUsed && next.reads == prior.reads && next.readsAfterRecovery == prior.readsAfterRecovery
                    if ((!read && !recover) || next.reads > 4 || (!next.recoveryUsed && next.reads > 3) || next.readsAfterRecovery > 1) return@transaction false
                    write(db, current.copy(attempts = current.attempts.dropLast(1) + active.copy(budget = next)))
                    true
                }
            }.getOrDefault(false)
        }
    }

    fun updatePhase(operationId: String, phase: ReplayPhase, reason: ReplayTerminationReason? = null): ReplayOperation = mutate(operationId) { op ->
        require(phase != ReplayPhase.COMPLETED && phase != ReplayPhase.PARTIAL) { "Use receipt-backed finish" }
        check(!op.phase.terminal) { "Begin an explicit retry before updating a final operation" }
        op.copy(phase = phase, reason = reason, finishedAt = if (phase.terminal) now() else null)
    }

    fun confirmConnection(operationId: String, epoch: Long): ReplayOperation = mutate(operationId) {
        it.copy(confirmedConnectionEpoch = epoch, confirmedConnectionAt = now())
    }

    fun setFence(operationId: String, processSessionId: String, sequence: Long): ReplayOperation = mutate(operationId) {
        check(!it.collectionSealed)
        require(sequence >= 0)
        it.copy(fenceSessionId = processSessionId, fenceSequence = sequence)
    }

    fun markCollected(operationId: String, source: ReplayCollectionSource): ReplayOperation = mutate(operationId) {
        it.copy(collected = it.collected + source)
    }

    /** No source may be marked complete when capacity, fence or snapshot persistence failed. */
    fun sealCollection(operationId: String): Boolean = transaction { db ->
        val op = requireOperation(db, operationId)
        if (op.collectionSealed) return@transaction true
        if (op.fenceSessionId == null || op.fenceSequence == null || !op.collected.containsAll(ReplayCollectionSource.entries)) return@transaction false
        write(db, op.copy(collectionSealed = true))
        true
    }

    fun registerItems(operationId: String, items: List<ReplayOperationItem>): ReplayOperation = mutate(operationId) { op ->
        check(!op.collectionSealed) { "Replay membership is frozen" }
        check(!op.phase.terminal)
        var members = op.items
        for (candidate in items) {
            require(candidate.result == null) { "Results require committed receipts" }
            val match = members.indexOfFirst { it.itemId == candidate.itemId || candidate.itemId in it.aliases ||
                (candidate.eventId != null && it.eventId == candidate.eventId) }
            members = if (match < 0) members + candidate else members.mapIndexed { index, member ->
                if (index != match) member else {
                    require(member.eventId == null || candidate.eventId == null || member.eventId == candidate.eventId)
                    member.copy(eventId = member.eventId ?: candidate.eventId,
                        aliases = member.aliases + candidate.aliases + candidate.itemId - member.itemId)
                }
            }
        }
        op.copy(items = members)
    }

    /** Resolving an existing input may collapse aliases after sealing, but never adds a member. */
    fun resolveItemEvent(operationId: String, itemId: String, eventId: String): ReplayOperation = mutate(operationId) { op ->
        val item = op.items.firstOrNull { it.itemId == itemId || itemId in it.aliases }
            ?: throw IllegalArgumentException("Unknown replay member")
        require(item.eventId == null || item.eventId == eventId)
        val other = op.items.firstOrNull { it.itemId != item.itemId && it.eventId == eventId }
        if (other == null) op.copy(items = op.items.map { if (it.itemId == item.itemId) it.copy(eventId = eventId) else it })
        else op.copy(items = op.items.filterNot { it.itemId == item.itemId }.map {
            if (it.itemId == other.itemId) it.copy(aliases = it.aliases + item.aliases + item.itemId,
                result = it.result ?: item.result) else it
        })
    }

    /** Import an entire ledger snapshot atomically. A failed write never publishes completion. */
    fun applyReceipts(operationId: String, receipts: List<ReplayOperationReceipt>): ReplayOperation = mutate(operationId) { op ->
        check(op.reason != ReplayTerminationReason.BOOK_CHANGED)
        var members = op.items
        for (receipt in receipts) {
            require(receipt.bookGeneration == op.bookGeneration) { "Receipt belongs to another ledger" }
            val index = members.indexOfFirst { it.itemId == receipt.itemId || receipt.itemId in it.aliases }
            require(index >= 0) { "Receipt is outside frozen membership" }
            val member = members[index]
            require(member.eventId == null || receipt.eventId == member.eventId)
            // Replay receipts are immutable; never downgrade a committed success on retry.
            val aliasDuplicate = member.aliases.isNotEmpty() && member.eventId != null &&
                member.result in setOf(ReplayItemResult.BOOKED, ReplayItemResult.ALREADY_PROCESSED) &&
                receipt.result in setOf(ReplayItemResult.BOOKED, ReplayItemResult.ALREADY_PROCESSED)
            require(member.result == null || member.result == ReplayItemResult.FAILED || member.result == receipt.result || aliasDuplicate)
            val result = if (aliasDuplicate && (member.result == ReplayItemResult.BOOKED || receipt.result == ReplayItemResult.BOOKED))
                ReplayItemResult.BOOKED else receipt.result
            members = members.mapIndexed { i, item -> if (i == index) item.copy(eventId = item.eventId ?: receipt.eventId, result = result) else item }
        }
        op.copy(items = members)
    }

    fun finish(operationId: String, reason: ReplayTerminationReason? = null): ReplayOperation = mutate(operationId) { op ->
        if (op.reason == ReplayTerminationReason.BOOK_CHANGED) return@mutate op
        val complete = op.collectionSealed && op.summary.unfinished == 0 && reason == null
        val phase = when {
            complete -> ReplayPhase.COMPLETED
            op.items.any { it.result != null && it.result != ReplayItemResult.FAILED } -> ReplayPhase.PARTIAL
            else -> ReplayPhase.FAILED
        }
        op.copy(phase = phase, reason = reason, finishedAt = now())
    }

    fun dismiss(operationId: String): ReplayOperation = mutate(operationId) {
        check(it.phase.terminal)
        it.copy(dismissed = true)
    }

    /** Run once at startup before consumers. Unsealed process-local fences cannot be recovered. */
    fun recoverInterrupted(currentBookGeneration: String) = transaction { db ->
        all(db).forEach { op ->
            when {
                op.bookGeneration != currentBookGeneration && (!op.phase.terminal || op.canRetry) ->
                    write(db, op.copy(phase = ReplayPhase.INTERRUPTED, reason = ReplayTerminationReason.BOOK_CHANGED, finishedAt = now()))
                !op.phase.terminal && !op.collectionSealed ->
                    write(db, op.copy(phase = ReplayPhase.INTERRUPTED, reason = ReplayTerminationReason.INTERRUPTED, finishedAt = now()))
            }
        }
    }

    /** Live restore fencing must not apply startup's lost in-memory-fence rule to other operations. */
    fun invalidateBookGeneration(currentBookGeneration: String) = transaction { db ->
        all(db).filter { it.bookGeneration != currentBookGeneration && (!it.phase.terminal || it.canRetry) }.forEach {
            write(db, it.copy(phase = ReplayPhase.INTERRUPTED, reason = ReplayTerminationReason.BOOK_CHANGED, finishedAt = now()))
        }
    }

    fun referencedIntakeIds(): Set<String> = all(helper.readableDatabase)
        .filter { !it.superseded && (!it.phase.terminal || it.canRetry) }
        .flatMap { it.items }.flatMap { listOf(it.itemId) + it.aliases }.toSet()

    /** Caller removes matching ledger receipts and then calls acknowledgeCleanup. Interrupted
     * cross-database cleanup is redelivered on the next prune, never silently lost.
     */
    fun prune(): List<String> = transaction { db ->
        val ids = all(db).filter { it.phase.terminal && (it.dismissed || it.superseded || !it.manual) &&
            it.finishedAt != null && now() - it.finishedAt >= RETENTION }.map { it.operationId }
        ids.forEach { id ->
            db.insertWithOnConflict("replay_cleanup", null, ContentValues().apply { put("operation_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("replay_operations", "operation_id=?", arrayOf(id))
        }
        db.rawQuery("SELECT operation_id FROM replay_cleanup ORDER BY operation_id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    fun acknowledgeCleanup(operationIds: List<String>) = transaction { db ->
        operationIds.forEach { db.delete("replay_cleanup", "operation_id=?", arrayOf(it)) }
    }

    override fun close() = helper.close()
    private fun mutate(id: String, change: (ReplayOperation) -> ReplayOperation): ReplayOperation = transaction { db ->
        change(requireOperation(db, id)).also { write(db, it) }
    }
    private fun requireOperation(db: SQLiteDatabase, id: String) = requireNotNull(read(db, id)) { "Unknown replay operation" }
    private fun read(db: SQLiteDatabase, id: String): ReplayOperation? = db.rawQuery(
        "SELECT document FROM replay_operations WHERE operation_id=?", arrayOf(id)
    ).use { if (it.moveToFirst()) decode(it.getString(0)) else null }
    private fun all(db: SQLiteDatabase): List<ReplayOperation> = db.rawQuery("SELECT document FROM replay_operations", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
    }
    private fun write(db: SQLiteDatabase, operation: ReplayOperation) {
        val values = ContentValues().apply {
            put("operation_id", operation.operationId); put("manual", if (operation.manual) 1 else 0)
            put("created_at", operation.createdAt); put("document", encode(operation).toString())
        }
        if (db.update("replay_operations", values, "operation_id=?", arrayOf(operation.operationId)) == 0)
            db.insertOrThrow("replay_operations", null, values)
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        val result: T
        try { result = block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        changes.update { it + 1 }
        return result
    }

    private fun encode(op: ReplayOperation) = JSONObject().apply {
        put("id", op.operationId); put("manual", op.manual); put("created", op.createdAt); put("book", op.bookGeneration)
        put("phase", op.phase.name); put("reason", op.reason?.name); put("connectionEpoch", op.confirmedConnectionEpoch)
        put("connectionAt", op.confirmedConnectionAt); put("fenceSession", op.fenceSessionId); put("fenceSequence", op.fenceSequence)
        put("collected", JSONArray(op.collected.map { it.name })); put("sealed", op.collectionSealed)
        put("dismissed", op.dismissed); put("superseded", op.superseded); put("finished", op.finishedAt)
        put("items", JSONArray(op.items.map { item -> JSONObject().apply {
            put("id", item.itemId); put("event", item.eventId); put("result", item.result?.name); put("aliases", JSONArray(item.aliases.toList()))
        } }))
        put("attempts", JSONArray(op.attempts.map { attempt -> JSONObject().apply {
            put("id", attempt.attemptId); put("created", attempt.createdAt); put("elapsed", attempt.createdElapsed)
            put("boot", attempt.bootCount); put("deadline", attempt.deadlineAt); put("reads", attempt.budget.reads)
            put("recovery", attempt.budget.recoveryUsed); put("postReads", attempt.budget.readsAfterRecovery)
            put("process", attempt.processSessionId)
        } }))
    }
    private fun decode(document: String): ReplayOperation {
        val obj = JSONObject(document)
        fun string(name: String): String? = if (obj.has(name) && !obj.isNull(name)) obj.getString(name) else null
        fun long(name: String): Long? = if (obj.has(name) && !obj.isNull(name)) obj.getLong(name) else null
        val items = obj.getJSONArray("items")
        val attempts = obj.getJSONArray("attempts")
        val collected = obj.getJSONArray("collected")
        return ReplayOperation(obj.getString("id"), obj.getBoolean("manual"), obj.getLong("created"), obj.getString("book"),
            ReplayPhase.valueOf(obj.getString("phase")), string("reason")?.let(ReplayTerminationReason::valueOf),
            long("connectionEpoch"), long("connectionAt"), string("fenceSession"), long("fenceSequence"),
            (0 until collected.length()).map { ReplayCollectionSource.valueOf(collected.getString(it)) }.toSet(), obj.getBoolean("sealed"),
            (0 until items.length()).map { i -> val item = items.getJSONObject(i); val aliases = item.getJSONArray("aliases")
                ReplayOperationItem(item.getString("id"), if (item.has("event")) item.getString("event") else null,
                    if (item.has("result")) ReplayItemResult.valueOf(item.getString("result")) else null,
                    (0 until aliases.length()).map { aliases.getString(it) }.toSet()) },
            (0 until attempts.length()).map { i -> val attempt = attempts.getJSONObject(i)
                ReplayAttempt(attempt.getString("id"), attempt.getLong("created"), attempt.getLong("elapsed"),
                    if (attempt.has("boot")) attempt.getInt("boot") else null, attempt.getLong("deadline"),
                    ReplayBudgetState(attempt.getInt("reads"), attempt.getBoolean("recovery"), attempt.getInt("postReads")),
                    if (attempt.has("process")) attempt.getString("process") else null) },
            obj.getBoolean("dismissed"), obj.getBoolean("superseded"), long("finished"))
    }

    companion object {
        private const val ATTEMPT_DURATION = 150_000L
        private const val RETENTION = 30L * 24 * 60 * 60 * 1_000
        private val changes = MutableStateFlow(0L)
        private val PROCESS_SESSION = UUID.randomUUID().toString()
    }
}
