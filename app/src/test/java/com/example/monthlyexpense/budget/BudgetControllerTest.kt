package com.example.monthlyexpense.budget

import com.example.monthlyexpense.*
import com.example.monthlyexpense.budget.api.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.*
import com.example.monthlyexpense.refresh.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetControllerTest {
    private class Writes : BudgetWritePort {
        val monthly = mutableListOf<Pair<YearMonth, Long>>(); val daily = mutableListOf<Long>()
        var accepted = true; var error: Exception? = null; var gate: CompletableDeferred<Unit>? = null
        override suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean {
            monthly += month to amountCents; gate?.await(); error?.let { throw it }; return accepted
        }
        override suspend fun setDailyBudget(amountCents: Long) {
            daily += amountCents; gate?.await(); error?.let { throw it }
        }
    }
    private class Fixture(scope: CoroutineScope) {
        val common = MutationControllerFixture(scope); val state = MutableSlice(BudgetOperationState()); val writes = Writes()
        var date = LocalDate.of(2026, 10, 31); val closed = mutableListOf<BudgetKind>()
        val controller = BudgetController(state, writes, common.epoch, BudgetNavigation { closed += it; true },
            common.runner, common.coordinator, common.lock, { date })
        suspend fun restore() = common.restore { controller.invalidateForRestore(); state.value = BudgetOperationState() }
    }

    @Test fun monthlySamplesDateWhenPersistenceRunsAndDailyUsesItsOwnPort() = runTest {
        val f = Fixture(this)
        f.controller.setBudget(BudgetKind.MONTHLY, 123)
        f.date = LocalDate.of(2026, 11, 1); advanceUntilIdle()
        f.controller.setBudget(BudgetKind.DAILY, 456); advanceUntilIdle()
        assertEquals(listOf(YearMonth.of(2026, 11) to 123L), f.writes.monthly)
        assertEquals(listOf(456L), f.writes.daily)
        assertEquals(listOf(BudgetKind.MONTHLY, BudgetKind.DAILY), f.closed)
        assertTrue(f.common.refreshes.all { it == RefreshScope.HOME to setOf(DataDomain.SETTINGS) })
    }

    @Test fun boundsAreValidatedAndPendingSuppressesOtherKindEvenWithInvalidAmount() = runTest {
        val f = Fixture(this)
        for (amount in listOf(-1L, MoneyLimits.MAX_CENTS + 1)) { f.controller.setBudget(BudgetKind.MONTHLY, amount); advanceUntilIdle() }
        assertTrue(f.writes.monthly.isEmpty()); assertEquals(2, f.common.messages().size)
        f.writes.gate = CompletableDeferred(); f.controller.setBudget(BudgetKind.DAILY, 0)
        f.controller.setBudget(BudgetKind.MONTHLY, -1); runCurrent()
        assertEquals(listOf(0L), f.writes.daily); assertEquals(2, f.common.messages().size); assertTrue(f.state.value.saving)
        f.writes.gate!!.complete(Unit); advanceUntilIdle()
        f.controller.setBudget(BudgetKind.MONTHLY, MoneyLimits.MAX_CENTS); advanceUntilIdle()
        assertEquals(MoneyLimits.MAX_CENTS, f.writes.monthly.single().second)
    }

    @Test fun repositoryRejectionOrFailureKeepsDialogAndDoesNotRefresh() = runTest {
        val f = Fixture(this); f.writes.accepted = false
        f.controller.setBudget(BudgetKind.MONTHLY, 1); advanceUntilIdle()
        f.writes.error = IllegalStateException("storage failure")
        f.controller.setBudget(BudgetKind.DAILY, 2); advanceUntilIdle()
        assertEquals(listOf("预算金额无效", "预算保存失败，请重试"), f.common.messages())
        assertTrue(f.closed.isEmpty()); assertTrue(f.common.refreshes.isEmpty()); assertFalse(f.state.value.saving)
    }

    @Test fun pendingWaitsForRefreshAndSavedRefreshFailureUsesDistinctMessage() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>()
        f.common.refresh = { gate.await(); RefreshResult.FAILED }
        f.controller.setBudget(BudgetKind.DAILY, 2); runCurrent()
        assertEquals(listOf(BudgetKind.DAILY), f.closed); assertTrue(f.state.value.saving)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(f.state.value.saving); assertEquals(listOf("操作已保存，但页面刷新失败"), f.common.messages())
    }

    @Test fun restoreRejectsQueuedBothKindsAndDisplayedEpochAndDurableGateProtectNewCommands() = runTest {
        for (kind in BudgetKind.entries) {
            val f = Fixture(this); val gate = CompletableDeferred<Unit>()
            val holding = launch { f.common.coordinator.runMutation(f.common.coordinator.mutationTicket()!!) { gate.await() } }
            runCurrent(); f.controller.setBudget(kind, 10); runCurrent()
            val before = f.state.value; f.controller.invalidateForRestore(); assertEquals(before, f.state.value)
            val restoring = launch { f.restore() }; runCurrent(); gate.complete(Unit); holding.join(); restoring.join(); advanceUntilIdle()
            f.common.epoch.value = 0; f.controller.setBudget(kind, 20); advanceUntilIdle()
            assertTrue(f.writes.monthly.isEmpty()); assertTrue(f.writes.daily.isEmpty()); assertTrue(f.common.events.isEmpty())
            f.common.epoch.value = f.common.coordinator.currentEpoch
            f.common.coordinator.setDurableRestorePending(true); f.controller.setBudget(kind, 30); advanceUntilIdle()
            assertFalse(f.state.value.saving); assertTrue(f.closed.isEmpty())
        }
    }

    @Test fun oldFinallyCannotClearNewPendingAfterRestore() = runTest {
        val f = Fixture(this); val old = CompletableDeferred<Unit>(); val next = CompletableDeferred<Unit>()
        f.common.refresh = { old.await(); RefreshResult.FAILED }; f.controller.setBudget(BudgetKind.MONTHLY, 1); runCurrent()
        f.restore(); f.common.refresh = { next.await(); RefreshResult.APPLIED }
        f.controller.setBudget(BudgetKind.DAILY, 2); runCurrent(); old.complete(Unit); runCurrent()
        assertTrue(f.state.value.saving); assertTrue(f.common.events.isEmpty())
        next.complete(Unit); advanceUntilIdle(); assertFalse(f.state.value.saving)
    }

    @Test fun cancellationBeforeStartAndDuringWriteReleasesPendingWithoutMessage() = runTest {
        for (start in listOf(false, true)) {
            val job = SupervisorJob(); val f = Fixture(CoroutineScope(StandardTestDispatcher(testScheduler) + job))
            f.writes.gate = CompletableDeferred(); f.controller.setBudget(BudgetKind.DAILY, 1)
            if (start) runCurrent(); job.cancel(); advanceUntilIdle()
            assertFalse(f.state.value.saving); assertTrue(f.common.events.isEmpty()); assertTrue(f.closed.isEmpty())
        }
    }
}
