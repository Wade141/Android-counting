package com.example.monthlyexpense.refresh

import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.history.api.HistoryRefreshPort
import com.example.monthlyexpense.history.api.HistoryRefreshPlan
import com.example.monthlyexpense.history.api.HistoryPageRead
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.notification.api.SettingGenerations
import com.example.monthlyexpense.notification.api.SettingsSnapshotPort
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import com.example.monthlyexpense.refresh.api.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal class HomeRefreshController(
    private val scope: CoroutineScope,
    private val reads: HomeReadPort,
    private val history: HistoryRefreshPort,
    private val settings: SettingsSnapshotPort,
    private val display: HomeDisplayPort,
    private val commit: HomeSnapshotCommit,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val events: UiEventSink,
    private val ioDispatcher: CoroutineDispatcher,
    private val today: () -> LocalDate,
    private val zoneId: () -> ZoneId,
) : RefreshCommands, MutationRefreshPort, RestoreRefreshPort {
    private val refreshMerger = RefreshRequestMerger()
    private var lastAppliedMonth: YearMonth? = null
    private var lastHomeSnapshot: ExpenseHomeSnapshot? = null
    private var lastAppliedDate: LocalDate? = null
    private var lastAppliedZone: ZoneId? = null
    private var failedRefreshDomains = emptySet<DataDomain>()

    fun updateNotificationState(state: NotificationListenerConnectionState) = display.updateNotificationState(state)
    fun refreshDomains(domains: Set<DataDomain>) {
        val relevant = (domains + failedRefreshDomains) - DataDomain.REVIEW
        if (relevant.isEmpty()) return
        request(RefreshRequest(if (DataDomain.LEDGER in relevant) RefreshScope.ALL else RefreshScope.HOME,
            display.notificationState, relevant), true, false)
    }
    override fun request(request: RefreshRequest, reportPageFailure: Boolean, notificationStateIsExternal: Boolean) {
        if (coordinator.readTicket() == null) return
        enqueueRefresh(QueuedRefresh(request, reportPageFailure = reportPageFailure,
            notificationStateIsExternal = notificationStateIsExternal))
    }
    override suspend fun afterMutation(scope: RefreshScope, domains: Set<DataDomain>): RefreshResult {
        val waiter = CompletableDeferred<RefreshResult>()
        enqueueRefresh(QueuedRefresh(RefreshRequest(scope, display.notificationState, domains), listOf(waiter), false, false))
        return waiter.await()
    }
    /** Detaches old requests without UI publication or early waiter callbacks. */
    fun invalidateForRestore(): RefreshInvalidation {
        val pending = refreshMerger.invalidatePending()
        return RefreshInvalidation { pending?.waiters?.forEach { it.complete(RefreshResult.STALE) } }
    }
    private fun emitMessage(message: UiMessage) { events.emit(UiEvent.ShowMessage(message)) }

    private fun enqueueRefresh(entry: QueuedRefresh) {
        val first = refreshMerger.offer(entry) ?: return
        val started = AtomicBoolean(false)
        val job = scope.launch {
            started.set(true)
            drainRefreshes(first)
        }
        // A cancelled scope can prevent the body (including its finally) from ever running.
        job.invokeOnCompletion {
            if (!started.get()) {
                val pending = refreshMerger.cancelDrain()
                first.waiters.forEach { it.complete(RefreshResult.STALE) }
                pending?.waiters?.forEach { it.complete(RefreshResult.STALE) }
            }
        }
    }

    private suspend fun drainRefreshes(first: QueuedRefresh) {
        var current: QueuedRefresh? = first
        var cancelled = false
        try {
            while (current != null) {
                val active = current.copy(request = current.request.copy(domains = current.request.domains + failedRefreshDomains))
                val result = performRefresh(active)
                if (result == RefreshResult.FAILED) failedRefreshDomains = failedRefreshDomains + active.request.domains
                else if (result == RefreshResult.APPLIED) failedRefreshDomains = failedRefreshDomains - active.request.domains
                active.waiters.forEach { waiter -> waiter.complete(result) }
                if (
                    result == RefreshResult.FAILED &&
                    active.reportPageFailure &&
                    active.waiters.isEmpty()
                ) {
                    emitMessage(UiMessage("页面刷新失败，请重试"))
                }
                current = refreshMerger.nextOrFinish()
            }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            if (cancelled) {
                val pending = refreshMerger.cancelDrain()
                current?.waiters?.forEach { waiter ->
                    waiter.complete(RefreshResult.STALE)
                }
                pending?.waiters?.forEach { waiter ->
                    waiter.complete(RefreshResult.STALE)
                }
            }
        }
    }

    private suspend fun performRefresh(entry: QueuedRefresh): RefreshResult {
        val ticket = coordinator.readTicket() ?: return RefreshResult.STALE
        val refreshDate = today()
        val historyPlan = when (
            val prepared = coordinator.commitReadIfCurrent(ticket) {
                history.prepareRefresh(entry.request.scope, refreshDate, lastAppliedMonth)
            }
        ) {
            CoordinatedMutation.Rejected -> return RefreshResult.STALE
            is CoordinatedMutation.Executed -> prepared.value
        }
        return try {
            when (val read = coordinator.runRead(ticket) {
                val settingGenerations = settings.captureGenerations()
                RefreshReadWithSettingGenerations(
                    read = loadRefreshRead(refreshDate, historyPlan, entry.request.domains + failedRefreshDomains),
                    settings = settingGenerations
                )
            }) {
                CoordinatedMutation.Rejected -> RefreshResult.STALE
                is CoordinatedMutation.Executed -> when (
                    val commit = coordinator.commitReadIfCurrent(ticket) { displayedDataEpoch ->
                        applyHomeSnapshot(
                            snapshot = read.value.read.homeSnapshot,
                            appliedDate = read.value.read.date,
                            appliedZone = read.value.read.zone,
                            notificationState = if (entry.notificationStateIsExternal) {
                                entry.request.notificationState
                            } else {
                                display.notificationState
                            },
                            displayedDataEpoch = displayedDataEpoch,
                            appliedMonth = YearMonth.from(refreshDate),
                            settingsGenerationsAtLoadStart = read.value.settings,
                            history = history.reduceRefresh(
                                read.value.read.historyRead,
                                historyPlan
                            )
                        )
                        if (read.value.read.historyRead is HistoryPageRead.Failed) {
                            RefreshResult.FAILED
                        } else {
                            RefreshResult.APPLIED
                        }
                    }
                ) {
                    CoordinatedMutation.Rejected -> RefreshResult.STALE
                    is CoordinatedMutation.Executed -> commit.value
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coordinator.commitReadIfCurrent(ticket) { history.failRefresh(historyPlan) }
            if (coordinator.isReadCurrent(ticket)) {
                RefreshResult.FAILED
            } else {
                RefreshResult.STALE
            }
        }
    }

    override suspend fun refreshWithinRestore() {
        val refreshDate = today()
        val historyPlan = history.prepareRefresh(RefreshScope.ALL, refreshDate, lastAppliedMonth)
        val settingGenerations = settings.captureGenerations()
        val refreshRead = try {
            loadRefreshRead(refreshDate, historyPlan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            history.failRefresh(historyPlan)
            throw error
        }
        applyHomeSnapshot(
            snapshot = refreshRead.homeSnapshot,
            appliedDate = refreshRead.date,
            appliedZone = refreshRead.zone,
            notificationState = display.notificationState,
            displayedDataEpoch = coordinator.currentEpoch,
            appliedMonth = YearMonth.from(refreshDate),
            settingsGenerationsAtLoadStart = settingGenerations,
            history = history.reduceRefresh(refreshRead.historyRead, historyPlan)
        )
        (refreshRead.historyRead as? HistoryPageRead.Failed)?.let { throw it.error }
    }

    private suspend fun loadRefreshRead(
        refreshDate: LocalDate,
        historyPlan: HistoryRefreshPlan,
        domains: Set<com.example.monthlyexpense.data.DataDomain> = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER, com.example.monthlyexpense.data.DataDomain.SETTINGS)
    ): RefreshRead {
        val refreshZone = zoneId()
        val homeSnapshot = withContext(ioDispatcher) {
            val baseline = coordinator.persistedSettings
            val previous = lastHomeSnapshot?.copy(autoBookkeepingEnabled = baseline.autoBookkeepingEnabled,
                weChatEnabled = baseline.weChatEnabled, alipayEnabled = baseline.alipayEnabled)
            if (coordinator.isDurableRestorePending) {
                reads.loadReadOnlyHomeSnapshot(refreshDate, refreshZone)
            } else if (previous == null || !display.initialLoadComplete || lastAppliedDate != refreshDate || lastAppliedZone != refreshZone) {
                reads.loadHomeSnapshot(refreshDate, refreshZone)
            } else reads.refreshHomeSnapshot(refreshDate, refreshZone, previous, domains)
        }
        val historyRead = history.readForRefresh(historyPlan)
        return RefreshRead(homeSnapshot, historyRead, refreshDate, refreshZone)
    }

    private fun applyHomeSnapshot(
        snapshot: ExpenseHomeSnapshot,
        appliedDate: LocalDate,
        appliedZone: ZoneId,
        notificationState: NotificationListenerConnectionState,
        displayedDataEpoch: Long,
        appliedMonth: YearMonth,
        settingsGenerationsAtLoadStart: SettingGenerations,
        history: HistoryUiState
    ) = com.example.monthlyexpense.notification.NotificationMetrics.measure(com.example.monthlyexpense.notification.PipelineMetric.UI_APPLY) {
        coordinator.updatePersistedSettings(snapshot.settingsBaseline())
        lastHomeSnapshot = snapshot
        lastAppliedDate = appliedDate
        lastAppliedZone = appliedZone
        lastAppliedMonth = appliedMonth
        commit.apply(
            snapshot = snapshot,
            history = history,
            notificationState = notificationState,
            settings = settings.mergeSnapshot(snapshot, settingsGenerationsAtLoadStart),
            displayedDataEpoch = displayedDataEpoch
        )
    }

}

private data class RefreshRead(
    val homeSnapshot: ExpenseHomeSnapshot,
    val historyRead: HistoryPageRead,
    val date: LocalDate,
    val zone: ZoneId
)

private data class RefreshReadWithSettingGenerations(
    val read: RefreshRead,
    val settings: SettingGenerations
)

private fun ExpenseHomeSnapshot.settingsBaseline(): ForegroundSettingsBaseline =
    ForegroundSettingsBaseline(
        autoBookkeepingEnabled = autoBookkeepingEnabled,
        weChatEnabled = weChatEnabled,
        alipayEnabled = alipayEnabled
    )

