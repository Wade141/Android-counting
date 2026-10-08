package com.example.monthlyexpense.notification

import android.content.Context
import androidx.work.*
import com.example.monthlyexpense.AppContainer
import com.example.monthlyexpense.backup.*
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.decision.DecisionAction
import com.example.monthlyexpense.notification.intake.*
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.repository.*
import com.example.monthlyexpense.notification.work.NotificationIntakeWorker
import com.example.monthlyexpense.notification.work.PaymentImportScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owns intake independently of a listener instance. Database operations are confined to IO callers. */
class NotificationIntakeRuntime(private val context: Context, private val container: AppContainer,
    private val payloadCipher: PayloadCipher? = null) : BackupRestoreLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { NotificationInboxDatabase(context) }
    private val cipher by lazy { payloadCipher ?: NotificationPayloadCipher(context) }
    val inbox by lazy { NotificationInboxRepository(database, cipher) }
    val policies by lazy { NotificationSourceGeneration(database, cipher) }
    private val protocol get() = container.database.backupDao.notificationProtocol
    val transferLock = Mutex()
    private val initializationLock = Any()
    @Volatile private var initialized = false
    @Volatile private var legacyBindingsReady = false
    @Volatile private var generation = "initializing"
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val manager get() = WorkManager.getInstance(context)
    private val imports by lazy { PaymentImportScheduler(context) }
    private val lastFailureSignal = AtomicLong(Long.MIN_VALUE)
    private val batchScheduler by lazy {
        NotificationIntakeScheduler(scope, inbox, object : IntakeBatchWork {
            override suspend fun state(batchId: String): IntakeWorkState {
                val previous = manager.getWorkInfosForUniqueWorkFlow("notification-intake:$batchId").first()
                return when {
                    previous.isEmpty() -> IntakeWorkState.MISSING
                    previous.all { it.state.isFinished } -> IntakeWorkState.FINISHED
                    else -> IntakeWorkState.ACTIVE
                }
            }
            override suspend fun enqueue(batchId: String, initialDelayMillis: Long) {
                val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
                    .setInputData(workDataOf("batch_id" to batchId))
                    .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS).build()
                manager.enqueueUniqueWork("notification-intake:$batchId", ExistingWorkPolicy.KEEP, request).await()
            }
        }, reconcile = { batchId -> transferLock.withLock {
            inbox.batchMembers(batchId).filter { it.state == IntakeState.PROCESSING }.forEach { inbox.retry(it.intakeId) }
        } }, onFailure = { NotificationPipelineLog.event("intake_schedule_failed") })
    }
    val intake by lazy { NotificationIntake(scope, inbox, policies, { generation },
        { wakeups.trySend(Unit) }, { notifyIntakeFailure() },
        clock = { IntakeClock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
            policies.snapshot().sources.values.firstOrNull()?.enabledAt?.bootCount) }) }

    private fun notifyIntakeFailure() {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val previous = lastFailureSignal.get()
        if ((previous == Long.MIN_VALUE || elapsed-previous >= 30_000) && lastFailureSignal.compareAndSet(previous,elapsed)) {
            val count = runCatching { inbox.failureCount() }.getOrNull()
            NotificationPipelineLog.event("intake_save_failed count=${count ?: "unknown"}")
            container.notifications.requestAutomaticReplay()
        }
    }

    init {
        scope.launch {
            for (ignored in wakeups) {
                try { initialize(); schedulePending() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: RuntimeException) { NotificationPipelineLog.event("intake_schedule_failed type=${error.javaClass.simpleName}") }
            }
        }
    }

    suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        initializeBlocking()
        if (!legacyBindingsReady && inbox.restoreBarrierId() == null && protocol.pendingRestore() == null) {
            val coordinator = container.persistenceCoordinator
            val ticket = coordinator.mutationTicket() ?: return@withContext
            coordinator.runMutation(ticket) { bindLegacyIfReady() }
        }
    }

    /** Source-setting mutations already own the non-reentrant ledger mutex. */
    fun initializeWithinCoordinator() { initializeBlocking(); bindLegacyIfReady() }

    private fun bindLegacyIfReady() {
        if (legacyBindingsReady || inbox.restoreBarrierId() != null || protocol.pendingRestore() != null) return
        val sourceState = policies.snapshot()
        NotificationEventRepository(container.database).bindLegacyGenerations(
            sourceState.sources.mapKeys { it.key.name }.mapValues { it.value.generation },
            sourceState.sources.filter { sourceState.globalEnabled && it.value.selected }.keys.map { it.name }.toSet())
        legacyBindingsReady = true
    }

    private fun initializeBlocking(): Unit = synchronized(initializationLock) {
        if (initialized) return@synchronized
        val settings = container.autoSettings
        policies.initialize(settings.isEnabled, settings.isWeChatEnabled, settings.isAlipayEnabled, IntakeClock.now(context))
        generation = protocol.bookGeneration()
        inbox.setBookGeneration(generation)
        container.replayOperations.recoverInterrupted(generation)
        inbox.recoverProcessing()
        settings.policyRead = {
            val current = policies.snapshot()
            Triple(current.globalEnabled, current.sources[PaymentSource.WECHAT]?.selected ?: true,
                current.sources[PaymentSource.ALIPAY]?.selected ?: true)
        }
        settings.policyChange = { enabled, wechat, alipay ->
            val clock = IntakeClock.now(context)
            if (enabled != null) policies.setEnabled(enabled, clock)
            if (wechat != null) policies.setSourceEnabled(PaymentSource.WECHAT, wechat, clock)
            if (alipay != null) policies.setSourceEnabled(PaymentSource.ALIPAY, alipay, clock)
            mirrorSettings()
            scope.launch { refreshBoundaries(); schedulePending() }
        }
        initialized = true
        val pending = protocol.pendingRestore()
        if (pending != null) {
            container.persistenceCoordinator.setDurableRestorePending(true)
            container.backupManager.continuePendingRestore()
        } else {
            val barrier = inbox.restoreBarrierId()
            val latest = protocol.latestRestore()
            if (barrier != null && latest?.restoreId == barrier && latest.bookGeneration != generation) {
                container.persistenceCoordinator.setDurableRestorePending(true)
            } else {
                inbox.setRestoreBarrier(null)
                container.persistenceCoordinator.setDurableRestorePending(false)
            }
        }
        mirrorSettings()
    }

    private fun mirrorSettings() {
        val snapshot = policies.snapshot()
        if (!container.autoSettings.restoreFromBackup(snapshot.globalEnabled,
                snapshot.sources[PaymentSource.WECHAT]?.selected ?: true,
                snapshot.sources[PaymentSource.ALIPAY]?.selected ?: true))
            NotificationPipelineLog.event("source_settings_mirror_pending")
    }

    fun offer(raw: RawNotification) {
        // Offer is synchronous and bounded. It copies no Service reference and owns its own lifetime.
        intake.offer(raw)
        scope.launch {
            try { initialize(); refreshBoundaries(); wakeups.trySend(Unit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RuntimeException) { NotificationPipelineLog.event("intake_initialization_failed type=${error.javaClass.simpleName}") }
        }
    }

    fun onAvailable() { scope.launch {
        try { initialize(); refreshBoundaries(); wakeups.trySend(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) { NotificationPipelineLog.event("intake_recovery_failed type=${error.javaClass.simpleName}") }
    } }

    private val boundaryLock = Mutex()
    suspend fun refreshBoundaries(): Unit = withContext(Dispatchers.IO) {
        if (!initialized || inbox.restoreBarrierId() != null || !boundaryLock.tryLock()) return@withContext
        try {
            val coordinator = container.persistenceCoordinator
            val rearmTicket = coordinator.mutationTicket() ?: return@withContext
            val rearmed = coordinator.runMutation(rearmTicket) { policies.rearmUncertainBoundaries(IntakeClock.now(context)) }
            if (rearmed !is CoordinatedMutation.Executed) return@withContext
            val pending = rearmed.value.sources.values.filter { it.state == SourcePolicyState.ARMING }
            if (pending.isEmpty()) return@withContext
            val snapshot = container.notifications.snapshots.read() as? SnapshotResult.Success
            if (snapshot == null) {
                val ticket = coordinator.mutationTicket() ?: return@withContext
                coordinator.runMutation(ticket) {
                    pending.forEach { policies.boundaryFailed(it.source, it.generation, "SNAPSHOT_UNAVAILABLE") }
                }
                return@withContext
            }
            var completed = false
            pending.forEach { policy ->
                val ticket = coordinator.mutationTicket() ?: return@forEach
                val result = coordinator.runMutation(ticket) {
                    inbox.restoreBarrierId() == null && snapshot.snapshot.epoch == PaymentNotificationListener.connectionEpoch &&
                        policies.completeBoundary(policy.source, policy.generation,
                        snapshot.snapshot.notifications, IntakeClock.now(context))
                }
                if ((result as? CoordinatedMutation.Executed)?.value == true) completed = true
            }
            if (completed) {
                // Old STAGED work may have waited while its source was ARMING. Consumers recheck generations.
                com.example.monthlyexpense.notification.work.enqueueStagedEvents(context)
                wakeups.trySend(Unit)
            }
        } finally { boundaryLock.unlock() }
    }

    suspend fun schedulePending(): Unit = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext
        inbox.maintenance()
        if (inbox.restoreBarrierId() != null) return@withContext
        classifyQuarantined()
        pruneCompletedMetadata()
        batchScheduler.schedulePending()
    }

    private suspend fun pruneCompletedMetadata(): Unit = transferLock.withLock {
        val coordinator = container.persistenceCoordinator
        val ticket = coordinator.mutationTicket() ?: return@withLock
        coordinator.runMutation(ticket) {
            val operations = container.replayOperations
            val obsolete = operations.prune()
            if (obsolete.isNotEmpty()) {
                ReplayReceiptRepository(container.database, NotificationEventRepository(container.database)).deleteOperations(obsolete)
                operations.acknowledgeCleanup(obsolete)
            }
            inbox.pruneTerminalMappings(operations.referencedIntakeIds())
        }
        Unit
    }

    /** Ordinary chat messages received while a boundary is unknown are not payment review items. */
    suspend fun classifyQuarantined(): Unit = withContext(Dispatchers.IO) { transferLock.withLock {
        initialize()
        val coordinator = container.persistenceCoordinator
        val ticket = coordinator.mutationTicket() ?: return@withLock
        val expectedBook = protocol.bookGeneration()
        var changed = false
        inbox.listQuarantined().forEach { row ->
            val raw = inbox.readPayload(row.intakeId) ?: return@forEach
            val decision = PaymentDecisionEngine(container.paymentRules.snapshot()).evaluate(raw)
            if (decision.action != DecisionAction.IGNORE) return@forEach
            val result = coordinator.runMutation(ticket) {
                val current = inbox.find(row.intakeId)
                if (protocol.bookGeneration() != expectedBook || current?.state != IntakeState.QUARANTINED ||
                    current.sourceGeneration != row.sourceGeneration || inbox.restoreBarrierId() != null) false
                else if (NotificationEventRepository(container.database).dispositionReason(decision.eventId) != null) false
                else { inbox.discard(row.intakeId); true }
            }
            if ((result as? CoordinatedMutation.Executed)?.value == true) changed = true
        }
        if (changed) container.dataChanges.publish(setOf(com.example.monthlyexpense.data.DataDomain.REVIEW))
    } }

    suspend fun drainBatch(batchId: String): Unit = withContext(Dispatchers.IO) {
        initialize()
        if (inbox.restoreBarrierId() != null) return@withContext
        inbox.batchMembers(batchId).forEach { record -> ensureStaged(record.intakeId) }
        inbox.finishBatch(batchId)
        wakeups.trySend(Unit)
        Unit
    }

    suspend fun ensureStaged(id: String, enqueueImport: Boolean = true): String? = withContext(Dispatchers.IO) { transferLock.withLock {
        initialize()
        var record = inbox.find(id) ?: return@withLock null
        if (record.state == IntakeState.HANDED_OFF) return@withLock record.eventId
        if (record.state != IntakeState.PENDING) return@withLock null
        if (inbox.restoreBarrierId() != null) return@withLock null
        val policy = policies.snapshot().sources[record.source] ?: return@withLock null
        if (!policies.allows(record.source, record.sourceGeneration)) {
            if (policy.generation != record.sourceGeneration || policy.state == SourcePolicyState.OFF) inbox.discard(id)
            return@withLock null
        }
        val currentBook = protocol.bookGeneration()
        if (record.bookGeneration != currentBook) { inbox.quarantine(id, QuarantineReason.BOOK_CHANGED); return@withLock null }
        val coordinator = container.persistenceCoordinator
        val ticket = coordinator.mutationTicket() ?: return@withLock null
        try {
            val raw = inbox.readPayload(id) ?: return@withLock null
            val result = coordinator.runMutation(ticket) {
                if (!policies.allows(record.source, record.sourceGeneration) ||
                    protocol.bookGeneration() != currentBook || inbox.restoreBarrierId() != null) return@runMutation null
                record = inbox.claim(id) ?: return@runMutation null
                val decision = PaymentDecisionEngine(container.paymentRules.snapshot()).evaluate(raw)
                val stored = NotificationEventRepository(container.database).stageForScheduling(decision, currentBook, policy.generation)
                if (stored.result == StoreResult.REJECTED) null else decision.eventId
            }
            val event = (result as? CoordinatedMutation.Executed)?.value
            if (event != null) {
                inbox.acknowledgeHandoff(id, event)
                if (enqueueImport) imports.enqueueEvent(event)
            } else inbox.retry(id)
            event
        } catch (cancelled: CancellationException) { inbox.retry(id); throw cancelled }
        catch (error: RuntimeException) { inbox.retry(id); NotificationPipelineLog.event("intake_handoff_failed type=${error.javaClass.simpleName}"); null }
    } }

    suspend fun confirmQuarantined(id: String, expectedBook: String, amount: Long, name: String): StoreResult =
        withContext(Dispatchers.IO) { transferLock.withLock {
            initialize()
            val record = inbox.find(id) ?: return@withLock StoreResult.REJECTED
            if (record.state != IntakeState.QUARANTINED || expectedBook != protocol.bookGeneration()) return@withLock StoreResult.REJECTED
            val policy = policies.snapshot().sources[record.source] ?: return@withLock StoreResult.REJECTED
            if (!policies.snapshot().globalEnabled || !policy.selected) return@withLock StoreResult.REJECTED
            val raw = inbox.readPayload(id) ?: return@withLock StoreResult.REJECTED
            val ticket = container.persistenceCoordinator.mutationTicket() ?: return@withLock StoreResult.REJECTED
            val result = container.persistenceCoordinator.runMutation(ticket) {
                val current = inbox.find(id) ?: return@runMutation StoreResult.REJECTED
                val currentPolicy = policies.snapshot().sources[current.source] ?: return@runMutation StoreResult.REJECTED
                if (protocol.bookGeneration() != expectedBook || inbox.restoreBarrierId() != null ||
                    current.state != IntakeState.QUARANTINED || current.sourceGeneration != record.sourceGeneration ||
                    !policies.snapshot().globalEnabled || !currentPolicy.selected || currentPolicy.state == SourcePolicyState.OFF ||
                    currentPolicy.generation != policy.generation) return@runMutation StoreResult.REJECTED
                val decision = PaymentDecisionEngine(container.paymentRules.snapshot()).evaluate(raw)
                val confirmed = container.notificationEvents()
                    .confirmQuarantined(decision, expectedBook, policy.generation, amount, name)
                if (confirmed.result in setOf(StoreResult.INSERTED, StoreResult.DUPLICATE)) inbox.acknowledgeHandoff(id, decision.eventId)
                confirmed.result
            }
            (result as? CoordinatedMutation.Executed)?.value ?: StoreResult.REJECTED
        } }

    suspend fun ignoreQuarantined(id: String, expectedBook: String): Boolean = withContext(Dispatchers.IO) { transferLock.withLock {
        initialize()
        val record = inbox.find(id) ?: return@withLock false
        if (record.state !in setOf(IntakeState.QUARANTINED, IntakeState.EXPIRED, IntakeState.FAILED)) return@withLock false
        val raw = inbox.readPayload(id)
        val ticket = container.persistenceCoordinator.mutationTicket() ?: return@withLock false
        val result = container.persistenceCoordinator.runMutation(ticket) {
            val current = inbox.find(id) ?: return@runMutation false
            if (protocol.bookGeneration() != expectedBook || inbox.restoreBarrierId() != null ||
                current.state !in setOf(IntakeState.QUARANTINED, IntakeState.EXPIRED, IntakeState.FAILED)) false
            else {
                val policy = policies.snapshot().sources[current.source] ?: return@runMutation false
                val events = container.notificationEvents()
                val ignored = when {
                    raw != null -> events.ignoreQuarantined(PaymentDecisionEngine(container.paymentRules.snapshot()).evaluate(raw), expectedBook, policy.generation)
                    current.eventId != null -> events.ignoreQuarantinedEvent(current.eventId, expectedBook)
                    else -> StoreResult.IGNORED
                }
                if (ignored == StoreResult.REJECTED) false else { inbox.discard(id); true }
            }
        }
        ((result as? CoordinatedMutation.Executed)?.value == true).also { changed ->
            if (changed) container.dataChanges.publish(setOf(com.example.monthlyexpense.data.DataDomain.REVIEW))
        }
    } }

    suspend fun continuePendingRestore(): BackupRestoreResult = withContext(Dispatchers.IO) {
        container.persistenceCoordinator.reconcileDurableRestore {
            container.backupManager.continuePendingRestore().also {
                container.dataChanges.publish(com.example.monthlyexpense.data.DataDomain.entries.toSet())
            }
        }
    }

    override fun begin(restoreId: String) {
        initializeBlocking()
        inbox.setRestoreBarrier(restoreId)
        container.persistenceCoordinator.setDurableRestorePending(true)
    }

    override fun applySources(settings: ExpenseBackupSettings) {
        val clock = IntakeClock.now(context)
        val restoreId = requireNotNull(inbox.restoreBarrierId()) { "Missing restore barrier" }
        policies.applyRestoredSettings(restoreId, settings.autoBookkeepingEnabled, settings.weChatEnabled,
            settings.alipayEnabled, clock)
        mirrorSettings()
    }

    override fun finish(restoreId: String, committed: Boolean) {
        generation = protocol.bookGeneration()
        inbox.setBookGeneration(generation)
        if (committed && protocol.latestRestore()?.let { it.restoreId == restoreId && it.bookGeneration == generation } == true) {
            container.replayOperations.invalidateBookGeneration(generation)
        }
        if (inbox.restoreBarrierId() == restoreId) inbox.setRestoreBarrier(null)
        container.persistenceCoordinator.setDurableRestorePending(false)
        onAvailable()
    }
}
