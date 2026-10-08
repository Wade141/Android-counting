package com.example.monthlyexpense.notification

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import com.example.monthlyexpense.AppContainer
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.notification.decision.PaymentDecisionEngine
import com.example.monthlyexpense.notification.repository.NotificationEventRepository
import com.example.monthlyexpense.notification.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Process-owned arbitration. Service destruction does not cancel reads or replay workers. */
class NotificationRuntime(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { WorkManager.getInstance(context) }
    private val recovery get() = NotificationListenerRecovery.forContext(context)
    private val settings get() = container.autoSettings
    private val repairPrefs = context.getSharedPreferences("notification_health_repair", Context.MODE_PRIVATE)
    val automaticSessions = AutomaticReplaySession(context.getSharedPreferences("notification_auto_replay_session", Context.MODE_PRIVATE))
    private val replayLock = Mutex()
    private val manualSubmissions = AtomicInteger(0)
    private val manualExecutions = AtomicInteger(0)
    @Volatile private var queuedManual = false
    @Volatile private var automaticDeferred = false
    val manualPending: Boolean get() = queuedManual || manualSubmissions.get() > 0 || manualExecutions.get() > 0

    val snapshots = NotificationSnapshotReader(
        capture = { withContext(Dispatchers.Main) { PaymentNotificationListener.snapshotConnection() } },
        currentEpoch = { PaymentNotificationListener.connectionEpoch },
        hasAccess = { NotificationAccessChecker.isGranted(context) },
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, "notification-snapshot").apply { isDaemon = true } },
        elapsed = SystemClock::elapsedRealtime,
        log = NotificationPipelineLog::event
    )
    val health = NotificationHealthCoordinator(
        enabled = { settings.isEnabled && NotificationAccessChecker.isGranted(context) },
        recovering = { replayLock.isLocked || manualPending || recovery.connectionState == NotificationListenerConnectionState.RECOVERING },
        epoch = { PaymentNotificationListener.connectionEpoch },
        read = { snapshots.read() }, repair = { requestRecovery(it, false) },
        elapsed = SystemClock::elapsedRealtime, log = NotificationPipelineLog::event
    )

    init {
        scope.launch {
            manager.getWorkInfosForUniqueWorkFlow(NotificationReplayRequests.WORK_NAME).collect { infos ->
                queuedManual = infos.any { !it.state.isFinished }
                if (!manualPending && automaticDeferred) {
                    automaticDeferred = false
                    requestAutomaticReplay()
                }
            }
        }
    }

    fun onForeground() { scope.launch {
        container.notificationIntake.onAvailable()
        try { health.check() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) { NotificationPipelineLog.event("health_check_failed type=${error.javaClass.simpleName}") }
    } }

    fun beginManualSubmission() { manualSubmissions.incrementAndGet() }
    suspend fun endManualSubmission() {
        try { refreshManualQueue() } finally { manualSubmissions.decrementAndGet() }
        if (!manualPending && automaticDeferred) { automaticDeferred = false; requestAutomaticReplay() }
    }
    private suspend fun refreshManualQueue() {
        queuedManual = manager.getWorkInfosForUniqueWorkFlow(NotificationReplayRequests.WORK_NAME).first().any { !it.state.isFinished }
    }

    fun requestAutomaticReplay() {
        if (!settings.isEnabled || !NotificationAccessChecker.isGranted(context)) return
        if (manualPending) { automaticDeferred = true; return }
        if (replayLock.isLocked || !automaticSessions.allowed()) return
        manager.enqueueUniqueWork(NotificationAutoReplayWorker.WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NotificationAutoReplayWorker>().build())
    }

    suspend fun requestRecovery(expectedEpoch: Long, manual: Boolean): Boolean = withContext(Dispatchers.Main) {
        if (!settings.isEnabled || !NotificationAccessChecker.isGranted(context)) return@withContext false
        if (recovery.connectionState == NotificationListenerConnectionState.RECOVERING) return@withContext true
        if (PaymentNotificationListener.connectionEpoch != expectedEpoch) return@withContext false
        if (manual) return@withContext recovery.onManualRepairRequested()
        if (PaymentNotificationListener.snapshotConnection() == null) {
            // Known disconnection needs ordinary rebind, not the extra fake-connection cooldown.
            if (PaymentNotificationListener.isListenerConnected) PaymentNotificationListener.invalidateConnectionForReset()
            return@withContext recovery.onBackgroundCheck()
        }
        val now = System.currentTimeMillis()
        val last = repairPrefs.getLong("last_accepted", 0L)
        if (last != 0L && (now < last || now - last < 300_000L)) {
            recovery.onHealthUnavailable(expectedEpoch)
            return@withContext false
        }
        // Persist before calling the system; restore the reservation only if no repair was accepted.
        if (!repairPrefs.edit().putLong("last_accepted", now).commit()) return@withContext false
        val accepted = recovery.onHealthCheckFailed(expectedEpoch)
        if (!accepted) repairPrefs.edit().putLong("last_accepted", last).commit()
        accepted
    }

    suspend fun replay(manual: Boolean, repair: Boolean, budget: ReplayBudget, operationId: String? = null): ReplayOutcome {
        container.notificationIntake.initialize()
        if (manual) manualExecutions.incrementAndGet() else refreshManualQueue()
        var locked = false
        var returned = false
        try {
            if (!manual && manualPending) { automaticDeferred = true; return ReplayOutcome.Yielded }
            suspend fun remainingBudget(): Long = if (operationId != null) withContext(Dispatchers.IO) {
                val operation = container.replayOperations.get(operationId)
                val durableRemaining = operation?.attempt?.let { container.replayOperations.remainingMillis(operationId, it.attemptId) } ?: 0L
                if (manual) durableRemaining else minOf(durableRemaining, automaticSessions.remainingMillis())
            } else if (manual) 150_000L else automaticSessions.remainingMillis()
            locked = withTimeoutOrNull(remainingBudget()) { replayLock.lock(); true } == true
            if (!locked) return ReplayOutcome.Failed(ReplayFailure.BUSY)
            if (!manual && manualPending) { automaticDeferred = true; return ReplayOutcome.Yielded }
            val ticket = container.persistenceCoordinator.mutationTicket()
                ?: return ReplayOutcome.Failed(ReplayFailure.BOOK_CHANGED)
            val remaining = remainingBudget()
            val acquisitionDeadline = SystemClock.elapsedRealtime() + remaining
            val runner = NotificationReplayCoordinator(
                enabled = { settings.isEnabled },
                shouldYield = { !manual && manualPending },
                epoch = { PaymentNotificationListener.connectionEpoch },
                read = { snapshots.read().also { health.onSnapshotChecked() } },
                recover = { expected, explicit ->
                    if ((!manual && manualPending) || !requestRecovery(expected, explicit)) false else {
                        withTimeoutOrNull(NotificationListenerRecovery.WORKER_WAIT_TIMEOUT_MS) {
                            while (recovery.connectionState == NotificationListenerConnectionState.RECOVERING) {
                                if (!settings.isEnabled || (!manual && manualPending) || !NotificationAccessChecker.isGranted(context)) return@withTimeoutOrNull false
                                delay(250L)
                            }
                            (recovery.connectionState == NotificationListenerConnectionState.CONNECTED).also { connected ->
                                if (connected && operationId != null) withContext(Dispatchers.IO) {
                                    container.replayOperations.confirmConnection(operationId, PaymentNotificationListener.connectionEpoch)
                                }
                            }
                        } == true
                    }
                },
                process = { snapshot ->
                    if (operationId != null) {
                        NotificationReplaySessionRunner(context, container).process(operationId, snapshot)
                    } else {
                    withContext(Dispatchers.IO) {
                        val imported = container.persistenceCoordinator.runMutation(ticket) {
                            val workContext = currentCoroutineContext()
                            if (!settings.isEnabled || !NotificationAccessChecker.isGranted(context) ||
                                snapshot.epoch != PaymentNotificationListener.connectionEpoch ||
                                SystemClock.elapsedRealtime() >= acquisitionDeadline) return@runMutation null
                            ExpenseDatabaseHelper(context).use { database ->
                                NotificationDecisionProcessor(PaymentDecisionEngine(container.paymentRules.snapshot()),
                                    container.notificationEvents(database))
                                    .replay(snapshot.notifications, System.currentTimeMillis(), settings::isSourceEnabled) {
                                        workContext.ensureActive()
                                        check(settings.isEnabled && NotificationAccessChecker.isGranted(context) &&
                                            snapshot.epoch == PaymentNotificationListener.connectionEpoch &&
                                            container.persistenceCoordinator.isCurrent(ticket))
                                    }
                            }
                        }
                        (imported as? CoordinatedMutation.Executed)?.value
                    }
                    }
                }, log = NotificationPipelineLog::event
            )
            val outcome = runner.run(budget, repair, remaining)
            returned = true
            if (!manual && manualPending) { automaticDeferred = true; return ReplayOutcome.Yielded }
            return outcome
        } finally {
            if (locked) replayLock.unlock()
            if (manual) {
                manualExecutions.decrementAndGet()
                if (returned) automaticSessions.completeForManual()
                automaticDeferred = !returned
                if (!manualPending && automaticDeferred) {
                    automaticDeferred = false
                    requestAutomaticReplay()
                }
            }
        }
    }
}
