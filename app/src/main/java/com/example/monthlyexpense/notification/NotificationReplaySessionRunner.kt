package com.example.monthlyexpense.notification

import android.content.Context
import com.example.monthlyexpense.AppContainer
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.notification.intake.*
import com.example.monthlyexpense.notification.replay.*
import com.example.monthlyexpense.notification.repository.*
import com.example.monthlyexpense.notification.parser.PaymentSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class ReplayYielded : IllegalStateException("Automatic replay yielded")

/** Freezes input identifiers and derives every booking count from a committed ledger receipt. */
class NotificationReplaySessionRunner(private val context: Context, private val container: AppContainer) {
    private val store get() = container.replayOperations
    private val intake get() = container.notificationIntake
    private val events get() = container.notificationEvents()
    private val receipts get() = ReplayReceiptRepository(container.database, events)
    private class ReplayStopped : IllegalStateException("Replay admission stopped")

    suspend fun prepareLocal(operationId: String) = withContext(Dispatchers.IO) {
        intake.initialize()
        reconcileReceipts(operationId)
        val operation = admit(operationId)
        if (operation.collectionSealed) return@withContext
        intake.classifyQuarantined()
        admit(operationId)
        store.updatePhase(operationId, ReplayPhase.COLLECTING)
        val fence = intake.intake.captureFence()
        store.setFence(operationId, fence.processSessionId, fence.sequence)
        val remaining = remaining(operation)
        val result = withTimeoutOrNull(remaining) { intake.intake.awaitFence(fence) }
        if (result == null) stop(operationId, ReplayTerminationReason.TIMEOUT)
        if (!result.complete) stop(operationId, ReplayTerminationReason.STORAGE)
        intake.transferLock.withLock {
            admit(operationId)
            val local = intake.inbox.records(
                "(process_session_id<>? OR sequence<=?) AND state NOT IN ('DISCARDED','EXPIRED')",
                arrayOf(fence.processSessionId, fence.sequence.toString()))
                .filter { ReplaySessionPolicy.includeLocal(it, operation.bookGeneration) }
                .filter { it.eventId == null || events.eventGeneration(it.eventId)?.bookGeneration == operation.bookGeneration }
            store.registerItems(operationId, local.map { ReplayOperationItem(it.intakeId, it.eventId) })
            store.markCollected(operationId, ReplayCollectionSource.INTAKE)
            var cursor: StagedCursor? = null
            while (true) {
                admit(operationId)
                val page = events.stagedPage(cursor)
                if (page.isEmpty()) break
                val currentBook = page.filter { events.eventGeneration(it.eventId)?.bookGeneration == operation.bookGeneration }
                store.registerItems(operationId, currentBook.map { ReplayOperationItem("event:${it.eventId}", it.eventId) })
                cursor = page.last()
            }
            store.markCollected(operationId, ReplayCollectionSource.STAGED)
        }
        processMembers(operationId, deferUnreadySources = true)
    }

    suspend fun process(operationId: String, snapshot: NotificationSnapshot): DecisionReplaySummary = withContext(Dispatchers.IO) {
        val previous = requireNotNull(store.get(operationId))
        if (previous.phase.terminal) return@withContext summary(previous, previous.phase != ReplayPhase.COMPLETED)
        try {
        var operation = admit(operationId)
        if (!operation.collectionSealed) {
            store.updatePhase(operationId, ReplayPhase.COLLECTING)
            val clock = IntakeClock.now(context)
            intake.policies.snapshot().sources.values.filter { it.selected && it.state == SourcePolicyState.ARMING }.forEach {
                coordinated(operationId) {
                    admit(operationId)
                    checkSnapshot(operationId, snapshot)
                    if (!intake.policies.completeBoundary(it.source, it.generation, snapshot.notifications, clock))
                        stop(operationId, ReplaySessionPolicy.boundaryFailure(intake.policies.snapshot()) ?: ReplayTerminationReason.READ)
                }
            }
            checkSnapshot(operationId, snapshot)
            checkBoundaries(operationId)
            val expectedSources = sourceGenerations()
            for ((index, raw) in snapshot.notifications.withIndex()) {
                currentCoroutineContext().ensureActive()
                admit(operationId, expectedSources)
                if (snapshot.epoch != PaymentNotificationListener.connectionEpoch) stop(operationId, ReplayTerminationReason.READ)
                if (!RecentPaymentNotificationFilter.isEligible(raw.packageName, raw.postTime, clock.wallTime)) continue
                val source = paymentSource(raw.packageName) ?: continue
                val policy = intake.policies.snapshot().sources[source] ?: stop(operationId, ReplayTerminationReason.INTERRUPTED)
                if (!policy.selected) continue
                when (val saved = intake.inbox.persist(raw, operation.bookGeneration, policy.generation, index.toLong(),
                    processSessionId = "replay:$operationId", clock = clock)) {
                    is IntakePersistResult.Saved -> store.registerItems(operationId, listOf(ReplayOperationItem(saved.intakeId)))
                    is IntakePersistResult.Rejected -> {
                        stop(operationId, when (saved.reason) {
                            IntakeFailure.CAPACITY, IntakeFailure.TOO_LARGE, IntakeFailure.QUEUE_FULL -> ReplayTerminationReason.CAPACITY
                            IntakeFailure.SOURCE_CHANGED -> ReplayTerminationReason.INTERRUPTED
                            IntakeFailure.SOURCE_DISABLED -> ReplayTerminationReason.DISABLED
                            else -> ReplayTerminationReason.STORAGE
                        })
                    }
                }
            }
            coordinated(operationId) {
                admit(operationId, expectedSources)
                checkSnapshot(operationId, snapshot)
                checkBoundaries(operationId)
                store.markCollected(operationId, ReplayCollectionSource.SNAPSHOT)
                if (!store.sealCollection(operationId)) stop(operationId, ReplayTerminationReason.INTERRUPTED)
            }
        }
        admit(operationId)
        store.updatePhase(operationId, ReplayPhase.PROCESSING)
        processMembers(operationId)
        operation = coordinated(operationId) {
            // Empty ranges must pass the same final checks as ranges containing payment items.
            admit(operationId)
            checkSnapshot(operationId, snapshot)
            checkBoundaries(operationId)
            store.finish(operationId)
        }
        summary(operation, operation.phase != ReplayPhase.COMPLETED)
        } catch (_: ReplayStopped) { summary(requireNotNull(store.get(operationId)), true) }
    }

    private suspend fun processMembers(operationId: String, deferUnreadySources: Boolean = false) {
        val operation = admit(operationId)
        for (original in operation.items) {
            currentCoroutineContext().ensureActive()
            val current = admit(operationId)
            val member = current.items.firstOrNull { it.itemId == original.itemId || original.itemId in it.aliases } ?: continue
            if (member.result != null && member.result != ReplayItemResult.FAILED) continue
            try {
                var eventId = member.eventId
                if (eventId == null) {
                    if (resolveLocalTerminal(operationId, member.itemId)) continue
                    eventId = intake.ensureStaged(member.itemId, enqueueImport = false)
                    admit(operationId)
                    if (eventId != null) store.resolveItemEvent(operationId, member.itemId, eventId)
                    else { resolveLocalTerminal(operationId, member.itemId); continue }
                }
                if (eventId == null) continue
                val canonical = requireNotNull(store.get(operationId)).items.first { it.eventId == eventId }
                if (canonical.result != null && canonical.result != ReplayItemResult.FAILED) continue
                val metadata = events.eventGeneration(eventId) ?: continue
                if (metadata.bookGeneration != operation.bookGeneration) continue
                val source = PaymentSource.valueOf(metadata.source)
                val receipt = coordinated(operationId) {
                    admit(operationId)
                    val policy = intake.policies.snapshot().sources[source] ?: stop(operationId, ReplayTerminationReason.INTERRUPTED)
                    if (policy.selected && policy.state == SourcePolicyState.ARMING) {
                        // Local preparation precedes the snapshot that can establish this boundary.
                        if (deferUnreadySources) return@coordinated null
                        stop(operationId, ReplaySessionPolicy.boundaryFailure(intake.policies.snapshot()) ?: ReplayTerminationReason.READ)
                    }
                    receipts.processForReplay(operationId, canonical.itemId, eventId, operation.bookGeneration,
                        if (intake.policies.allows(source, policy.generation)) policy.generation else -1L)
                } ?: continue
                coordinated(operationId) {
                    // The ledger already committed. Cache that fact even if the business deadline
                    // expired or a manual request arrived while its transaction was finishing.
                    if (container.database.backupDao.notificationProtocol.bookGeneration() != operation.bookGeneration)
                        stop(operationId, ReplayTerminationReason.BOOK_CHANGED)
                    store.applyReceipts(operationId, listOf(receipt.asOperationReceipt()))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: ReplayStopped) { throw stopped }
            catch (yielded: ReplayYielded) { throw yielded }
            catch (error: RuntimeException) {
                NotificationPipelineLog.event("replay_item_unfinished type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun ReplayItemReceipt.asOperationReceipt() = ReplayOperationReceipt(itemId, eventId, bookGeneration, when (result) {
        StoreResult.INSERTED -> ReplayItemResult.BOOKED
        StoreResult.DUPLICATE -> ReplayItemResult.ALREADY_PROCESSED
        StoreResult.REVIEW -> ReplayItemResult.REVIEW
        StoreResult.IGNORED -> ReplayItemResult.IGNORED
        StoreResult.REJECTED -> if (events.dispositionReason(eventId) == DispositionReason.SOURCE_DISABLED)
            ReplayItemResult.SOURCE_DISABLED else ReplayItemResult.FAILED
        StoreResult.STAGED -> ReplayItemResult.FAILED
    })

    private suspend fun resolveLocalTerminal(operationId: String, itemId: String): Boolean = intake.transferLock.withLock {
        coordinated(operationId) {
            val operation = admit(operationId)
            val record = intake.inbox.find(itemId) ?: return@coordinated false
            val result = when (record.state) {
                IntakeState.DISCARDED -> {
                    val policy = intake.policies.snapshot().sources[record.source]
                    if (policy == null || !policy.selected || policy.generation != record.sourceGeneration)
                        ReplayItemResult.SOURCE_DISABLED else ReplayItemResult.IGNORED
                }
                IntakeState.QUARANTINED -> {
                    val raw = intake.inbox.readPayload(itemId) ?: return@coordinated false
                    val decision = com.example.monthlyexpense.notification.decision.PaymentDecisionEngine(container.paymentRules.snapshot()).evaluate(raw)
                    if (decision.eventId.isNotBlank()) store.resolveItemEvent(operationId, itemId, decision.eventId)
                    if (decision.action == com.example.monthlyexpense.notification.decision.DecisionAction.IGNORE &&
                        events.dispositionReason(decision.eventId) == null) {
                        intake.inbox.discard(itemId)
                        ReplayItemResult.IGNORED
                    } else ReplayItemResult.REVIEW
                }
                else -> return@coordinated false
            }
            val latest = requireNotNull(store.get(operationId))
            val canonical = latest.items.first { it.itemId == itemId || itemId in it.aliases }
            if (canonical.result == null || canonical.result == ReplayItemResult.FAILED)
                store.applyReceipts(operationId, listOf(ReplayOperationReceipt(canonical.itemId, canonical.eventId, operation.bookGeneration, result)))
            true
        }
    }

    /** Run before a resumed attempt is rejected for budget expiry, so committed counts are visible. */
    suspend fun reconcileReceipts(operationId: String) = withContext(Dispatchers.IO) {
        val operation = store.get(operationId) ?: return@withContext
        if (operation.reason == ReplayTerminationReason.BOOK_CHANGED) return@withContext
        val ticket = container.persistenceCoordinator.mutationTicket() ?: return@withContext
        container.persistenceCoordinator.runMutation(ticket) {
            if (container.database.backupDao.notificationProtocol.bookGeneration() != operation.bookGeneration)
                return@runMutation
            val committed = receipts.receipts(operationId)
            if (committed.isNotEmpty()) store.applyReceipts(operationId, committed.map { it.asOperationReceipt() })
        }
    }

    private fun admit(operationId: String, expectedSources: Map<PaymentSource, Long>? = null): ReplayOperation {
        val operation = requireNotNull(store.get(operationId))
        if (operation.phase.terminal || operation.superseded) throw ReplayStopped()
        if (!operation.manual && container.notifications.manualPending) throw ReplayYielded()
        val remaining = remaining(operation)
        val reason = ReplaySessionPolicy.admissionFailure(operation.bookGeneration,
            container.database.backupDao.notificationProtocol.bookGeneration(), container.autoSettings.isEnabled,
            NotificationAccessChecker.isGranted(context), remaining, intake.inbox.restoreBarrierId() != null)
        if (reason != null) stop(operationId, reason)
        if (expectedSources != null && expectedSources != sourceGenerations()) stop(operationId, ReplayTerminationReason.INTERRUPTED)
        return operation
    }

    private fun remaining(operation: ReplayOperation): Long {
        val value = operation.attempt?.let { store.remainingMillis(operation.operationId, it.attemptId) } ?: 0L
        return if (operation.manual) value else minOf(value, container.notifications.automaticSessions.remainingMillis())
    }

    private fun sourceGenerations(): Map<PaymentSource, Long> = intake.policies.snapshot().sources
        .filterValues { it.selected }.mapValues { it.value.generation }

    private fun checkBoundaries(operationId: String) {
        ReplaySessionPolicy.boundaryFailure(intake.policies.snapshot())?.let { stop(operationId, it) }
    }

    private fun checkSnapshot(operationId: String, snapshot: NotificationSnapshot) {
        if (snapshot.epoch != PaymentNotificationListener.connectionEpoch) stop(operationId, ReplayTerminationReason.READ)
    }

    private fun stop(operationId: String, reason: ReplayTerminationReason): Nothing {
        val operation = requireNotNull(store.get(operationId))
        if (!operation.phase.terminal) {
            if (reason == ReplayTerminationReason.BOOK_CHANGED || reason == ReplayTerminationReason.INTERRUPTED)
                store.updatePhase(operationId, ReplayPhase.INTERRUPTED, reason)
            else store.finish(operationId, reason)
        }
        throw ReplayStopped()
    }

    private suspend fun <T> coordinated(operationId: String, action: () -> T): T {
        val ticket = container.persistenceCoordinator.mutationTicket() ?: stop(operationId, ReplayTerminationReason.INTERRUPTED)
        val result = container.persistenceCoordinator.runMutation(ticket) { action() }
        return when (result) {
            is CoordinatedMutation.Executed -> result.value
            else -> stop(operationId, ReplayTerminationReason.INTERRUPTED)
        }
    }

    private fun summary(op: ReplayOperation, incomplete: Boolean): DecisionReplaySummary = op.summary.let {
        DecisionReplaySummary(it.booked, it.alreadyProcessed, it.review, it.ignored,
            maxOf(it.unfinished, if (incomplete) 1 else 0))
    }
}

internal object ReplaySessionPolicy {
    fun admissionFailure(operationBook: String, currentBook: String, enabled: Boolean, permission: Boolean,
        remainingMillis: Long, restorePending: Boolean): ReplayTerminationReason? = when {
        operationBook != currentBook -> ReplayTerminationReason.BOOK_CHANGED
        restorePending -> ReplayTerminationReason.INTERRUPTED
        !enabled -> ReplayTerminationReason.DISABLED
        !permission -> ReplayTerminationReason.PERMISSION
        remainingMillis <= 0 -> ReplayTerminationReason.TIMEOUT
        else -> null
    }

    fun boundaryFailure(snapshot: SourcePolicySnapshot): ReplayTerminationReason? {
        if (!snapshot.initialized) return ReplayTerminationReason.INTERRUPTED
        if (!snapshot.globalEnabled) return ReplayTerminationReason.DISABLED
        val incomplete = snapshot.sources.values.firstOrNull { it.selected && it.state != SourcePolicyState.ON } ?: return null
        return when (incomplete.failure) {
            "CAPACITY" -> ReplayTerminationReason.CAPACITY
            "ENCRYPTION" -> ReplayTerminationReason.STORAGE
            else -> ReplayTerminationReason.READ
        }
    }

    fun includeLocal(record: IntakeRecord, bookGeneration: String): Boolean =
        // Handed-off pending events are collected by the STAGED scan. Re-reading completed
        // mappings from every previous operation would grow each replay with ledger history.
        record.state !in setOf(IntakeState.DISCARDED, IntakeState.EXPIRED, IntakeState.HANDED_OFF) &&
            (record.bookGeneration == bookGeneration || record.state == IntakeState.QUARANTINED)
}
