package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.*
import com.example.monthlyexpense.refresh.api.MutationRefreshPort
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundMutationRunnerTest {
    private class Slot : MutationSlot {
        var generation = 0L
        var pending = false
        override fun admit(): Long? { if (pending) return null; pending = true; return ++generation }
        override fun isCurrent(generation: Long) = this.generation == generation
        override fun finish(generation: Long) { if (isCurrent(generation)) pending = false }
        fun reset() { generation++; pending = false }
    }
    private class Fixture(scope: CoroutineScope) {
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        val slot = Slot()
        val events = mutableListOf<UiEvent>()
        var reads = 0
        var writes = 0
        var applied = 0
        var refresh: suspend () -> RefreshResult = { RefreshResult.APPLIED }
        val runner = ForegroundMutationRunner(scope, coordinator, Any(), object : MutationRefreshPort {
            override suspend fun afterMutation(scope: RefreshScope, domains: Set<DataDomain>): RefreshResult {
                assertEquals(RefreshScope.HOME, scope); assertEquals(setOf(DataDomain.LEDGER), domains)
                reads++; return refresh()
            }
        }, UiEventSink { events += it; true })
        fun launch(ticket: ForegroundMutationTicket = coordinator.mutationTicket()!!,
            validation: () -> UiMessage? = { null }, mutation: suspend () -> Boolean = { writes++; true }) {
            runner.launch(ticket, slot, validation, mutation,
                { MutationResult(it, if (it) null else UiMessage("rejected")) }, { applied++ },
                RefreshScope.HOME, setOf(DataDomain.LEDGER), UiMessage("failed"))
        }
        fun messages() = events.filterIsInstance<UiEvent.ShowMessage>().map { it.message.text }
    }

    @Test fun duplicateAdmissionIsSynchronousAndPendingWaitsForRefresh() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>()
        f.refresh = { gate.await(); RefreshResult.APPLIED }
        f.launch(); assertTrue(f.slot.pending)
        f.launch(validation = { error("duplicate must not validate") }); runCurrent()
        assertEquals(1, f.writes); assertEquals(1, f.applied); assertTrue(f.slot.pending)
        gate.complete(Unit); advanceUntilIdle(); assertFalse(f.slot.pending); assertEquals(1, f.reads)
    }

    @Test fun validationRejectionAndThrownValidationAlwaysReleaseTheSlot() = runTest {
        val f = Fixture(this)
        f.launch(validation = { UiMessage("invalid") })
        assertFalse(f.slot.pending); assertEquals(listOf("invalid"), f.messages())
        try { f.launch(validation = { throw IllegalArgumentException("bad input") }); fail() }
        catch (_: IllegalArgumentException) { }
        advanceUntilIdle(); assertFalse(f.slot.pending); assertEquals(0, f.writes); assertEquals(0, f.reads)
    }

    @Test fun businessRejectionDoesNotApplyOrRefreshAndFailureKeepsItsOwnMessage() = runTest {
        val f = Fixture(this)
        f.launch(mutation = { false }); advanceUntilIdle()
        f.launch(mutation = { error("write failed") }); advanceUntilIdle()
        assertEquals(listOf("rejected", "failed"), f.messages())
        assertEquals(0, f.applied); assertEquals(0, f.reads); assertFalse(f.slot.pending)
    }

    @Test fun refreshFailureReportsSavedButStaleRefreshDoesNotReportSuccessOrFailure() = runTest {
        val f = Fixture(this); f.refresh = { RefreshResult.FAILED }
        f.launch(); advanceUntilIdle()
        assertEquals(listOf("操作已保存，但页面刷新失败"), f.messages())
        f.events.clear(); f.refresh = { RefreshResult.STALE }
        f.launch(); advanceUntilIdle(); assertTrue(f.events.isEmpty()); assertFalse(f.slot.pending)
    }

    @Test fun queuedOldMutationNeverWritesReusedIdAfterRestoreAndOldFinallyKeepsNewPending() = runTest {
        val f = Fixture(this); val blocker = CompletableDeferred<Unit>(); val newRefresh = CompletableDeferred<Unit>()
        val holding = launch { f.coordinator.runMutation(f.coordinator.mutationTicket()!!) { blocker.await() } }
        runCurrent(); val oldTicket = f.coordinator.mutationTicket()!!
        f.launch(oldTicket); runCurrent()
        val restore = f.coordinator.beginRestore()!!; f.slot.reset()
        val restoring = launch { f.coordinator.runRestore(restore, { RestoreCommit.NotRestored }, {}) }
        blocker.complete(Unit); holding.join(); restoring.join()
        f.refresh = { newRefresh.await(); RefreshResult.APPLIED }
        f.launch(); runCurrent()
        assertEquals(1, f.writes); assertEquals(1, f.applied); assertTrue(f.slot.pending)
        assertTrue(f.events.isEmpty()); newRefresh.complete(Unit); advanceUntilIdle(); assertFalse(f.slot.pending)
    }

    @Test fun staleActiveCompletionCannotApplyOrClearNewGenerationPending() = runTest {
        val f = Fixture(this); val oldRefresh = CompletableDeferred<Unit>(); val newRefresh = CompletableDeferred<Unit>()
        f.refresh = { oldRefresh.await(); RefreshResult.FAILED }
        f.launch(); runCurrent(); assertTrue(f.slot.pending)
        val restore = f.coordinator.beginRestore()!!; f.slot.reset()
        f.coordinator.runRestore(restore, { RestoreCommit.NotRestored }, {})
        f.refresh = { newRefresh.await(); RefreshResult.APPLIED }
        f.launch(); runCurrent(); oldRefresh.complete(Unit); runCurrent()
        assertTrue(f.slot.pending); assertTrue(f.events.isEmpty())
        newRefresh.complete(Unit); advanceUntilIdle(); assertFalse(f.slot.pending)
    }

    @Test fun cancellationBeforeCoroutineStartsAndWhileWaitingAlwaysClearsPendingSilently() = runTest {
        for (start in listOf(false, true)) {
            val job = SupervisorJob(); val f = Fixture(CoroutineScope(StandardTestDispatcher(testScheduler) + job))
            f.launch(mutation = { awaitCancellation() })
            if (start) runCurrent()
            job.cancel(); advanceUntilIdle()
            assertFalse("start=$start", f.slot.pending); assertTrue(f.events.isEmpty())
        }
    }
}
