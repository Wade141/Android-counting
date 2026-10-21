package com.example.monthlyexpense.history

import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.history.api.*
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Independent history component: no VM, repository, Android, or database. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseHistoryControllerTest {
    private class State : SliceState<HistoryUiState> {
        val flow = MutableStateFlow(HistoryUiState())
        override val value get() = flow.value
        override fun update(transform: (HistoryUiState) -> HistoryUiState) = flow.update(transform)
    }
    private class Reads : HistoryReadPort {
        val cursors = mutableListOf<HistoryCursor?>()
        val months = mutableListOf<YearMonth?>()
        var read: suspend (HistoryCursor?, YearMonth?) -> HistoryPage = { _, _ -> HistoryPage(emptyList(), null, false) }
        override suspend fun loadHistoryPage(cursor: HistoryCursor?, limit: Int, month: YearMonth?): HistoryPage {
            assertEquals(50, limit)
            cursors += cursor; months += month
            return read(cursor, month)
        }
    }
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val state = State()
        val reads = Reads()
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        var visible = false
        val navigation = object : HistoryNavigation {
            override val isOpen get() = visible
            override fun open(history: HistoryUiState) { visible = true; state.update { history } }
        }
        val controller = ExpenseHistoryController(scope, reads, state, navigation, coordinator, Any(), dispatcher)
    }
    private fun record(id: Long) = ExpenseRecord(id, 100, ExpenseCategory("FOOD", "餐饮", 0, true, 0),
        "expense", "", id, ExpenseSource.MANUAL, null)

    @Test fun paginationDeduplicatesAndRetryUsesFailedCursorWithoutDroppingItems() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val cursor = HistoryCursor(2, 2)
        var fail = true
        f.reads.read = { after, _ ->
            if (after == null) HistoryPage(listOf(record(1), record(2), record(2)), cursor, true)
            else if (fail) throw IllegalStateException("page")
            else HistoryPage(listOf(record(2), record(3)), null, false)
        }
        f.controller.openHistory(); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), f.state.value.items.map { it.id })
        f.controller.loadMoreHistory(); f.controller.loadMoreHistory(); advanceUntilIdle()
        assertTrue(f.state.value.loadFailed)
        assertEquals(cursor, f.state.value.failedCursor)
        assertEquals(listOf(1L, 2L), f.state.value.items.map { it.id })
        fail = false
        f.controller.retryHistoryLoad(); advanceUntilIdle()
        assertEquals(listOf(null, cursor, cursor), f.reads.cursors)
        assertEquals(listOf(1L, 2L, 3L), f.state.value.items.map { it.id })
        assertFalse(f.state.value.loadingMore)
        assertFalse(f.state.value.hasMore)
        f.visible = false; f.controller.openHistory(); advanceUntilIdle()
        assertEquals(3, f.reads.cursors.size)
    }

    @Test fun selectedMonthInvalidatesInFlightPageAndKeepsNewMonthOwnership() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val march = YearMonth.of(2026, 3)
        f.reads.read = { _, month ->
            if (month == null) { gate.await(); HistoryPage(listOf(record(1)), null, false) }
            else HistoryPage(listOf(record(2)), null, false)
        }
        f.controller.openHistory(); runCurrent()
        f.controller.selectHistoryMonth(march); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(march, f.state.value.selectedMonth)
        assertEquals(listOf(2L), f.state.value.items.map { it.id })
        assertEquals(listOf(null, march), f.reads.months)
    }

    @Test fun refreshFailureKeepsPreviousPageAndStalePlanCannotOverrideNewSelection() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.state.update { HistoryUiState(items = listOf(record(1)), initialLoadComplete = true) }
        val plan = f.controller.prepareRefresh(RefreshScope.ALL, LocalDate.of(2026, 3, 1), YearMonth.of(2026, 2))
        f.controller.failRefresh(plan)
        assertEquals(listOf(1L), f.state.value.items.map { it.id })
        assertTrue(f.state.value.loadFailed)
        f.controller.retryHistoryLoad(); advanceUntilIdle()
        assertEquals(listOf<HistoryCursor?>(null), f.reads.cursors)
        val gate = CompletableDeferred<Unit>()
        f.reads.read = { _, _ -> gate.await(); HistoryPage(listOf(record(3)), null, false) }
        f.controller.selectHistoryMonth(YearMonth.of(2026, 4)); runCurrent()
        val before = f.state.value
        assertEquals(before, f.controller.reduceRefresh(HistoryPageRead.Loaded(HistoryPage(listOf(record(9)), null, false)), plan))
        f.controller.failRefresh(plan)
        assertEquals(before, f.state.value)
        gate.complete(Unit); advanceUntilIdle()
    }

    @Test fun restoreInvalidatesOldPageWithoutPublishingPartialState() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        f.reads.read = { _, _ -> gate.await(); HistoryPage(listOf(record(1)), null, false) }
        f.controller.openHistory(); runCurrent()
        val before = f.state.value
        val ticket = requireNotNull(f.coordinator.beginRestore())
        f.controller.invalidateForRestore()
        assertEquals(before, f.state.value)
        f.state.update { it.copy(loading = false, loadingMore = false) }
        val restore = async { f.coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {}) }
        gate.complete(Unit); advanceUntilIdle(); restore.await()
        assertTrue(f.state.value.items.isEmpty())
        assertFalse(f.state.value.loading)
    }
}
