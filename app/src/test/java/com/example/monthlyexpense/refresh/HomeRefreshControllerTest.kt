package com.example.monthlyexpense.refresh

import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.history.api.*
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.notification.api.*
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import com.example.monthlyexpense.refresh.api.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Each external capability is replaceable; no VM/container/database is constructed. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRefreshControllerTest {
    private class Reads : HomeReadPort {
        var full = 0; var partial = 0; var readOnly = 0
        val domains = mutableListOf<Set<DataDomain>>()
        var action: suspend () -> ExpenseHomeSnapshot = { snapshot(1) }
        override suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot { full++; return action() }
        override suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot { readOnly++; return action() }
        override suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: ZoneId, previous: ExpenseHomeSnapshot,
            domains: Set<DataDomain>): ExpenseHomeSnapshot { partial++; this.domains += domains; return action() }
    }
    private class History : HistoryRefreshPort {
        var current = HistoryUiState()
        var failure: Exception? = null
        var readCount = 0
        override fun prepareRefresh(requestedScope: RefreshScope, refreshDate: LocalDate, previousMonth: YearMonth?) =
            HistoryRefreshPlan(1, requestedScope == RefreshScope.ALL, current)
        override suspend fun readForRefresh(plan: HistoryRefreshPlan): HistoryPageRead {
            if (!plan.loadFirstPage) return HistoryPageRead.NotRequested
            readCount++
            return failure?.let { HistoryPageRead.Failed(it) } ?: HistoryPageRead.Loaded(HistoryPage(emptyList(), null, false))
        }
        override fun reduceRefresh(read: HistoryPageRead, plan: HistoryRefreshPlan): HistoryUiState =
            current.copy(loadFailed = read is HistoryPageRead.Failed, loading = false)
        override fun failRefresh(plan: HistoryRefreshPlan) { current = current.copy(loadFailed = true, loading = false) }
    }
    private class Display : HomeDisplayPort, HomeSnapshotCommit {
        override var initialLoadComplete = false
        override var notificationState = NotificationListenerConnectionState.NOT_AUTHORIZED
        var epoch = 0L
        var snapshot: ExpenseHomeSnapshot? = null
        var history = HistoryUiState()
        val samples = mutableListOf<Pair<Long, Long>>()
        override fun updateNotificationState(state: NotificationListenerConnectionState) { notificationState = state }
        override fun apply(snapshot: ExpenseHomeSnapshot, history: HistoryUiState, settings: AutoBookkeepingUiState,
            notificationState: NotificationListenerConnectionState, displayedDataEpoch: Long) {
            this.snapshot = snapshot; this.history = history; this.notificationState = notificationState
            epoch = displayedDataEpoch; initialLoadComplete = true
            samples += epoch to snapshot.monthlyBudgetCents
        }
    }
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val reads = Reads(); val history = History(); val display = Display()
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        val events = mutableListOf<UiEvent>()
        var date = LocalDate.of(2026, 3, 1)
        var zone = ZoneId.of("UTC")
        val settings = object : SettingsSnapshotPort {
            override fun captureGenerations() = SettingGenerations(0, 0, 0)
            override fun mergeSnapshot(snapshot: ExpenseHomeSnapshot, captured: SettingGenerations) = AutoBookkeepingUiState()
        }
        val controller = HomeRefreshController(scope, reads, history, settings, display, display,
            coordinator, UiEventSink { events += it; true }, dispatcher, { date }, { zone })
        fun request(scope: RefreshScope = RefreshScope.HOME) = controller.request(
            RefreshRequest(scope, NotificationListenerConnectionState.NOT_AUTHORIZED), true, true)
    }
    companion object {
        private fun snapshot(budget: Long) = ExpenseHomeSnapshot(emptyList(), emptyList(), budget, 0, 0, false, true, true)
    }

    @Test fun overlappingRequestsUseOneActiveAndOneTrailingRead() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.reads.action = { gate.await(); snapshot(1) }
        f.request(); runCurrent()
        repeat(100) { f.request() }
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, f.reads.full)
        assertEquals(1, f.reads.partial)
        assertEquals(0, f.history.readCount)
        assertTrue(f.display.initialLoadComplete)
    }

    @Test fun failureKeepsDisplayEpochAndRetriesFailedDomainsAcrossDateAndZoneChanges() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        assertEquals(RefreshResult.APPLIED, async { f.controller.afterMutation(RefreshScope.HOME, setOf(DataDomain.LEDGER)) }.await())
        f.reads.action = { throw IllegalStateException("home") }
        assertEquals(RefreshResult.FAILED, async { f.controller.afterMutation(RefreshScope.ALL, setOf(DataDomain.LEDGER)) }.await())
        assertEquals(1L, f.display.snapshot!!.monthlyBudgetCents)
        assertEquals(0L, f.display.epoch)
        assertTrue(f.history.current.loadFailed)
        f.reads.action = { snapshot(2) }
        f.controller.refreshDomains(setOf(DataDomain.SETTINGS)); advanceUntilIdle()
        assertEquals(setOf(DataDomain.LEDGER, DataDomain.SETTINGS), f.reads.domains.last())
        f.controller.refreshDomains(setOf(DataDomain.SETTINGS)); advanceUntilIdle()
        assertEquals(setOf(DataDomain.SETTINGS), f.reads.domains.last())
        f.date = f.date.plusDays(1); f.request(); advanceUntilIdle()
        f.zone = ZoneId.of("Asia/Shanghai"); f.request(); advanceUntilIdle()
        assertEquals(3, f.reads.full)
    }

    @Test fun historyFailurePublishesFreshHomeButReturnsFailedToMutationWaiter() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.history.failure = IllegalStateException("history")
        val result = async { f.controller.afterMutation(RefreshScope.ALL, setOf(DataDomain.LEDGER)) }.await()
        assertEquals(RefreshResult.FAILED, result)
        assertEquals(1L, f.display.snapshot!!.monthlyBudgetCents)
        assertTrue(f.display.history.loadFailed)
        assertTrue(f.events.isEmpty())
    }

    @Test fun restoreUsesDirectReadInsideExistingLockAndRejectsOldActiveAndQueuedResults() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.reads.action = { gate.await(); snapshot(1) }
        val active = async { f.controller.afterMutation(RefreshScope.HOME, setOf(DataDomain.LEDGER)) }
        runCurrent()
        val queued = async { f.controller.afterMutation(RefreshScope.ALL, setOf(DataDomain.SETTINGS)) }
        runCurrent()
        val ticket = requireNotNull(f.coordinator.beginRestore())
        val invalidated = f.controller.invalidateForRestore()
        assertFalse(queued.isCompleted)
        invalidated.completeStale(); runCurrent()
        assertEquals(RefreshResult.STALE, queued.await())
        val restore = async {
            withTimeout(5_000) { f.coordinator.runRestore(ticket,
                { f.reads.action = { snapshot(2) }; RestoreCommit.Restored(ForegroundSettingsBaseline(false, true, true)) },
                { f.controller.refreshWithinRestore() }) }
        }
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(RefreshResult.STALE, active.await())
        assertEquals(RestoreOutcome.RESTORED, restore.await())
        assertEquals(listOf(1L to 2L), f.display.samples)
        assertEquals(1, f.history.readCount)
        assertTrue(f.events.isEmpty())
    }

    @Test fun restoreHomeFailureKeepsOldEpochAndDurableGateUsesReadOnlyHome() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.request(); advanceUntilIdle()
        val ticket = requireNotNull(f.coordinator.beginRestore())
        f.reads.action = { throw IllegalStateException("restored read") }
        val outcome = f.coordinator.runRestore(ticket, { RestoreCommit.Restored(ForegroundSettingsBaseline(true, false, false)) },
            { f.controller.refreshWithinRestore() })
        assertEquals(RestoreOutcome.RESTORED_REFRESH_FAILED, outcome)
        assertEquals(0L, f.display.epoch)
        assertEquals(1L, f.display.snapshot!!.monthlyBudgetCents)
        f.coordinator.setDurableRestorePending(true)
        f.reads.action = { snapshot(2) }; f.request(); advanceUntilIdle()
        assertEquals(1, f.reads.readOnly)
        assertTrue(f.coordinator.isDurableRestorePending)
    }

    @Test fun alreadyCancelledScopeTerminatesWaiterInsteadOfLeavingItSuspended() = runTest {
        val job = Job().apply { cancel() }
        val f = Fixture(CoroutineScope(job + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        val result = withTimeoutOrNull(1_000) { f.controller.afterMutation(RefreshScope.HOME, setOf(DataDomain.LEDGER)) }
        assertEquals(RefreshResult.STALE, result)
        assertEquals(0, f.reads.full)
        assertTrue(f.events.isEmpty())
    }

    @Test fun realHistoryReadsWithinRestoreLockWithoutReenteringCoordinator() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = Fixture(this, dispatcher)
        var historyState = HistoryUiState(initialLoadComplete = true)
        var historyReads = 0
        val state = object : com.example.monthlyexpense.presentation.contracts.SliceState<HistoryUiState> {
            override val value get() = historyState
            override fun update(transform: (HistoryUiState) -> HistoryUiState) { historyState = transform(historyState) }
        }
        val history = com.example.monthlyexpense.history.ExpenseHistoryController(this,
            object : HistoryReadPort {
                override suspend fun loadHistoryPage(cursor: HistoryCursor?, limit: Int, month: YearMonth?): HistoryPage {
                    historyReads++
                    assertTrue(f.coordinator.isRestoring)
                    return HistoryPage(emptyList(), null, false)
                }
            }, state, object : HistoryNavigation {
                override val isOpen = false
                override fun open(history: HistoryUiState) { historyState = history }
            }, f.coordinator, Any(), dispatcher)
        val home = HomeRefreshController(this, f.reads, history, f.settings, f.display, f.display,
            f.coordinator, UiEventSink { true }, dispatcher, { f.date }, { f.zone })
        val ticket = requireNotNull(f.coordinator.beginRestore())
        val outcome = withTimeoutOrNull(1_000) {
            f.coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, { home.refreshWithinRestore() })
        }
        assertEquals(RestoreOutcome.NOT_RESTORED, outcome)
        assertEquals(1, historyReads)
        assertEquals(1, f.reads.full)
        assertEquals(1L, f.display.epoch)
        assertFalse(f.display.history.loading)
    }

    @Test fun cancellingDrainTerminatesActiveAndPendingWaitersWithoutOldErrorMessages() = runTest {
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val f = Fixture(scope, StandardTestDispatcher(testScheduler))
        f.reads.action = { awaitCancellation() }
        val active = async { f.controller.afterMutation(RefreshScope.HOME, setOf(DataDomain.LEDGER)) }
        runCurrent()
        val queued = async { f.controller.afterMutation(RefreshScope.ALL, setOf(DataDomain.LEDGER)) }
        runCurrent(); job.cancel(); advanceUntilIdle()
        assertEquals(RefreshResult.STALE, active.await())
        assertEquals(RefreshResult.STALE, queued.await())
        assertTrue(f.events.isEmpty())
        assertFalse(f.display.initialLoadComplete)
    }
}
