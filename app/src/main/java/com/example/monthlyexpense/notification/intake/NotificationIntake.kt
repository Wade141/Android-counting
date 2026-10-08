package com.example.monthlyexpense.notification.intake

import com.example.monthlyexpense.notification.RawNotification
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

sealed interface IntakeEnqueueResult {
    data class Accepted(val processSessionId: String, val sequence: Long) : IntakeEnqueueResult
    data class Rejected(val reason: IntakeFailure) : IntakeEnqueueResult
}
class IntakeFence internal constructor(val processSessionId: String, val sequence: Long,
    val rejectedCount: Long, internal val result: CompletableDeferred<IntakeFenceResult>,
    internal val baselineSequence: Long = 0, internal val baselineRejected: Long = 0,
    internal val baselineResult: CompletableDeferred<IntakeFenceResult>? = null)
data class IntakeFenceResult(val committedCount: Long, val failedCount: Long, val rejectedCount: Long,
    val interrupted: Boolean = false) {
    val complete: Boolean get() = failedCount == 0L && rejectedCount == 0L && !interrupted
}

/** Only immutable copies cross this entry point; no Service reference or disk access on offer. */
class NotificationIntake(scope: CoroutineScope, private val repository: NotificationInboxRepository,
    private val policies: NotificationSourceGeneration, private val bookGeneration: () -> String,
    private val onWake: () -> Unit, private val onFailure: () -> Unit = {},
    private val clock: () -> IntakeClock = { IntakeClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),null) }
) {
    val processSessionId: String = UUID.randomUUID().toString()
    private data class Candidate(val raw: RawNotification,val book: String,val source: Long,val sequence: Long,val clock: IntakeClock)
    private val lock = Any()
    private val queue = Channel<Candidate>(128)
    private val fences = mutableListOf<IntakeFence>()
    private var accepted = 0L
    private var completed = 0L
    private var failed = 0L
    private var rejected = 0L
    private var closed = false
    private var previousFenceSequence = 0L
    private var previousFenceRejected = 0L
    private var previousFenceResult: CompletableDeferred<IntakeFenceResult>? = null

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for(candidate in queue) {
                    val persisted = runCatching { repository.persist(candidate.raw,candidate.book,candidate.source,candidate.sequence,
                        processSessionId=processSessionId,clock=candidate.clock) }.getOrElse { IntakePersistResult.Rejected(IntakeFailure.STORAGE) }
                    synchronized(lock) {
                        completed = candidate.sequence
                        if(persisted is IntakePersistResult.Rejected) failed++
                        val iterator = fences.iterator()
                        while(iterator.hasNext()) {
                            val fence = iterator.next()
                            if(fence.sequence <= completed) {
                                fence.result.complete(IntakeFenceResult(fence.sequence-failed,failed,fence.rejectedCount))
                                iterator.remove()
                            }
                        }
                    }
                    if(persisted is IntakePersistResult.Saved) runCatching(onWake) else {
                        repository.recordFailure((persisted as IntakePersistResult.Rejected).reason)
                        runCatching(onFailure)
                    }
                }
            } finally {
                synchronized(lock) {
                    closed = true
                    queue.close()
                    fences.forEach { it.result.complete(IntakeFenceResult(completed-failed,failed+(it.sequence-completed),it.rejectedCount,true)) }
                    fences.clear()
                }
            }
        }
    }
    fun offer(raw: RawNotification): IntakeEnqueueResult {
        val source = paymentSource(raw.packageName) ?: return IntakeEnqueueResult.Rejected(IntakeFailure.UNSUPPORTED_SOURCE)
        val snapshot = policies.snapshot()
        val policy = snapshot.sources[source]
        if(snapshot.initialized && (policy == null || policy.state == SourcePolicyState.OFF)) return IntakeEnqueueResult.Rejected(IntakeFailure.SOURCE_DISABLED)
        val copy = raw.copy(textLines=raw.textLines.toList())
        val candidateClock = clock()
        return synchronized(lock) {
            if(closed) { rejected++; return@synchronized IntakeEnqueueResult.Rejected(IntakeFailure.CLOSED) }
            val sequence = accepted+1
            if(queue.trySend(Candidate(copy,bookGeneration(),policy?.generation ?: 0,sequence,candidateClock)).isSuccess) {
                accepted = sequence
                IntakeEnqueueResult.Accepted(processSessionId,sequence)
            } else {
                rejected++
                // Failure recovery stays off the listener's callback thread.
                failureSignal.trySend(Unit)
                IntakeEnqueueResult.Rejected(IntakeFailure.QUEUE_FULL)
            }
        }
    }
    private val failureSignal = Channel<Unit>(Channel.CONFLATED).also { signal ->
        scope.launch(Dispatchers.IO) {
            var reported = 0L
            for(ignored in signal) {
                val count = synchronized(lock) { rejected }
                repository.recordFailure(IntakeFailure.QUEUE_FULL,count-reported)
                reported = count
                runCatching(onFailure)
            }
        }
    }
    fun captureFence(): IntakeFence = synchronized(lock) {
        IntakeFence(processSessionId,accepted,rejected,CompletableDeferred(),
            previousFenceSequence,previousFenceRejected,previousFenceResult).also { fence ->
            previousFenceSequence = accepted
            previousFenceRejected = rejected
            previousFenceResult = fence.result
            when {
                closed -> fence.result.complete(IntakeFenceResult(completed-failed,failed+accepted-completed,rejected,true))
                completed >= accepted -> fence.result.complete(IntakeFenceResult(completed-failed,failed,rejected))
                else -> fences.add(fence)
            }
        }
    }
    /** Each collection attempt reports failures since the preceding fence; historical failures remain
     * in the durable aggregate, but cannot poison every subsequent user retry for this process. */
    suspend fun awaitFence(fence: IntakeFence): IntakeFenceResult {
        if(fence.processSessionId != processSessionId) return IntakeFenceResult(0,0,fence.rejectedCount,true)
        val result = fence.result.await()
        val baselineFailed = fence.baselineResult?.await()?.failedCount ?: 0
        val failures = (result.failedCount-baselineFailed).coerceAtLeast(0)
        return IntakeFenceResult((fence.sequence-fence.baselineSequence-failures).coerceAtLeast(0),failures,
            (result.rejectedCount-fence.baselineRejected).coerceAtLeast(0),result.interrupted)
    }
}
