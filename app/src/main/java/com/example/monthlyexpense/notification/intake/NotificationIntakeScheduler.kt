package com.example.monthlyexpense.notification.intake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class IntakeWorkState { MISSING, ACTIVE, FINISHED }
interface IntakeBatchWork {
    suspend fun state(batchId: String): IntakeWorkState
    /** Must await WorkManager's enqueue operation before returning. */
    suspend fun enqueue(batchId: String, initialDelayMillis: Long)
}

/** A continuing conflated consumer: an arrival during scheduling always leaves another wakeup. */
class NotificationIntakeScheduler(scope: CoroutineScope, private val repository: NotificationInboxRepository,
    private val work: IntakeBatchWork, private val reconcile: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis, private val onFailure: () -> Unit = {}) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scheduling = Mutex()
    init { scope.launch(Dispatchers.IO) { for(ignored in signals) runCatching { schedulePending() }.onFailure { onFailure() } } }
    fun wake() { signals.trySend(Unit) }
    suspend fun schedulePending() = scheduling.withLock {
        // At most 500 active payloads: bounded pass. Failed enqueue leaves READY for a later trigger.
        for(batch in repository.unfinishedBatches()) {
            when(work.state(batch.batchId)) {
                IntakeWorkState.ACTIVE -> repository.markBatchEnqueued(batch.batchId)
                IntakeWorkState.FINISHED -> { reconcile(batch.batchId); repository.finishBatch(batch.batchId) }
                IntakeWorkState.MISSING -> if(batch.state == "ENQUEUED") {
                    reconcile(batch.batchId); repository.finishBatch(batch.batchId)
                } else {
                    work.enqueue(batch.batchId,(batch.notBefore-now()).coerceAtLeast(0)); repository.markBatchEnqueued(batch.batchId)
                }
            }
        }
        repeat(NotificationInboxRepository.MAX_ITEMS) {
            val batch = repository.createBatch(now()) ?: return@withLock
            work.enqueue(batch.batchId,(batch.notBefore-now()).coerceAtLeast(0))
            repository.markBatchEnqueued(batch.batchId)
        }
    }
}
