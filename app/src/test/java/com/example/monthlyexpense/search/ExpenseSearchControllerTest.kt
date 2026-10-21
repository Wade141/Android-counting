package com.example.monthlyexpense.search

import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.search.api.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Pure component: own slice and capability Fakes, real coordinator; no VM/container/database. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseSearchControllerTest {
    private class State : SliceState<ExpenseSearchUiState> {
        val flow = MutableStateFlow(ExpenseSearchUiState())
        override val value get() = flow.value
        override fun update(transform: (ExpenseSearchUiState) -> ExpenseSearchUiState) = flow.update(transform)
    }
    private class Reads : SearchReadPort {
        val calls = mutableListOf<Pair<ExpenseSearchQuery, ExpenseSearchCursor?>>()
        var active = 0; var peak = 0
        var read: suspend (ExpenseSearchQuery, ExpenseSearchCursor?) -> ExpenseSearchPage = { q, _ -> page(1, q.keyword) }
        override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int): ExpenseSearchPage {
            assertEquals(50, limit)
            calls += query to cursor; active++; peak = maxOf(peak, active)
            return try { read(query, cursor) } finally { active-- }
        }
    }
    private class Fixture(scope: CoroutineScope) {
        val state = State(); val reads = Reads()
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        var searchOpen = false
        var date = LocalDate.of(2026, 9, 30)
        var zone = ZoneId.of("Asia/Shanghai")
        val navigation = object : SearchNavigation {
            override fun openSearch() { searchOpen = true; state.update { it.copy(editingExpense = null, isComposing = false) } }
            override fun closeSearchIfCurrent(): Boolean {
                if (!searchOpen) return false
                searchOpen = false
                state.update { it.copy(filterDraft = null, editingExpense = null, isComposing = false) }
                return true
            }
        }
        val editor = object : SearchEditorNavigation {
            override fun open(snapshot: SearchEditSnapshot) { searchOpen = false; state.update { it.copy(editingExpense = snapshot) } }
            override fun returnToSearch() { searchOpen = true; state.update { it.copy(editingExpense = null) } }
        }
        val controller = ExpenseSearchController(scope, reads, coordinator, state,
            { searchOpen || state.value.editingExpense != null }, navigation, editor, { date }, { zone })
        fun keywords() = reads.calls.map { it.first.keyword }
    }
    companion object {
        private fun record(id: Long, name: String = "茶") = ExpenseRecord(id, 100,
            ExpenseCategory("FOOD", "饮食", 0, true, 0), name, "", 1000, ExpenseSource.MANUAL, null)
        private fun page(id: Long = 1, name: String = "茶") = ExpenseSearchPage(listOf(record(id, name)), null, false, ExpenseSearchSummary(1, 100))
    }

    @Test fun debounceQueriesAt300msButNot299ms() = runTest {
        val f = Fixture(this); f.controller.open(); runCurrent()
        f.controller.keyword("茶", false)
        advanceTimeBy(299); runCurrent(); assertEquals(listOf(""), f.keywords())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("", "茶"), f.keywords())
        assertEquals("茶", f.state.value.items.single().name)
    }

    @Test fun compositionDoesNotQueryAndImeFlushesExactlyOnce() = runTest {
        val f = Fixture(this); f.controller.open(); runCurrent()
        f.controller.keyword("cha", true); advanceUntilIdle(); assertEquals(listOf(""), f.keywords())
        f.controller.keyword("茶", false); f.controller.submit(); advanceUntilIdle()
        assertEquals(listOf("", "茶"), f.keywords())
        f.controller.keyword(" 茶 ", false); advanceUntilIdle(); assertEquals(2, f.reads.calls.size)
    }

    @Test fun newestConditionsReplaceQueuedQueryAndOldFailureCannotPublish() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>()
        f.reads.read = { q, _ -> if (q.keyword.isEmpty()) { gate.await(); error("old") }; page(2, q.keyword) }
        f.controller.open(); runCurrent()
        f.controller.keyword("A", false); f.controller.submit(); runCurrent()
        f.controller.keyword("B", false); f.controller.submit(); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("", "B"), f.keywords()); assertEquals(1, f.reads.peak)
        assertEquals("B", f.state.value.items.single().name); assertNull(f.state.value.error)
    }

    @Test fun oldSuccessfulPageCannotFillResultsDuringNewKeywordDebounce() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>()
        f.reads.read = { q, _ -> if (q.keyword.isEmpty()) gate.await(); page(1, q.keyword) }
        f.controller.open(); runCurrent()
        f.controller.keyword("新", false)
        gate.complete(Unit); runCurrent()
        assertTrue(f.state.value.items.isEmpty()); assertNull(f.state.value.summary)
        advanceTimeBy(299); runCurrent(); assertEquals(listOf(""), f.keywords())
        advanceTimeBy(1); runCurrent()
        assertEquals("新", f.state.value.items.single().name)
        assertEquals(listOf("", "新"), f.keywords())
    }

    @Test fun reopeningKeepsConditionsAndCommitsAbandonedCompositionWithoutHiddenQueries() = runTest {
        val f = Fixture(this); f.controller.open(); advanceUntilIdle()
        f.controller.replaceFilters(ExpenseSearchFilters(categoryKeys = setOf("FOOD"))); advanceUntilIdle()
        f.controller.keyword("cha", true); f.controller.close()
        val count = f.reads.calls.size
        f.controller.dataChanged(setOf(DataDomain.LEDGER)); f.controller.submit(); advanceUntilIdle()
        assertEquals(count, f.reads.calls.size)
        f.controller.open(); advanceUntilIdle()
        assertEquals("cha", f.reads.calls.last().first.keyword)
        assertEquals(setOf("FOOD"), f.reads.calls.last().first.categoryKeys)
        assertFalse(f.state.value.isComposing)
        f.controller.dataChanged(setOf(DataDomain.REVIEW)); advanceUntilIdle()
        assertEquals(count + 1, f.reads.calls.size)
    }

    @Test fun failedAppendKeepsSummaryAndRetriesSameCursorWithoutDuplicates() = runTest {
        val f = Fixture(this); val cursor = ExpenseSearchCursor(1000, 2); var fail = true
        f.reads.read = { _, after ->
            if (after == null) ExpenseSearchPage(listOf(record(2)), cursor, true, ExpenseSearchSummary(2, 200))
            else if (fail) error("page") else ExpenseSearchPage(listOf(record(2), record(1)), null, false, null)
        }
        f.controller.open(); advanceUntilIdle(); f.controller.more(); f.controller.more(); advanceUntilIdle()
        assertEquals(listOf(2L), f.state.value.items.map { it.id }); assertEquals(cursor, f.state.value.failedCursor)
        assertEquals(ExpenseSearchSummary(2, 200), f.state.value.summary)
        fail = false; f.controller.retry(); advanceUntilIdle()
        assertEquals(listOf(null, cursor, cursor), f.reads.calls.map { it.second })
        assertEquals(listOf(2L, 1L), f.state.value.items.map { it.id }); assertFalse(f.state.value.loadingMore)
    }

    @Test fun editingSnapshotSurvivesDisappearingResultsAndDurableGateDisablesNewEdits() = runTest {
        val f = Fixture(this); f.controller.open(); advanceUntilIdle(); f.controller.edit(1)
        val captured = requireNotNull(f.state.value.editingExpense)
        f.reads.read = { _, _ -> ExpenseSearchPage(emptyList(), null, false, ExpenseSearchSummary(0, 0)) }
        f.controller.dataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle()
        assertTrue(f.state.value.items.isEmpty()); assertEquals(captured, f.state.value.editingExpense)
        f.controller.returnFromEdit(); assertTrue(f.searchOpen); assertNull(f.state.value.editingExpense)
        f.coordinator.setDurableRestorePending(true)
        f.reads.read = { _, _ -> page() }; f.controller.dataChanged(setOf(DataDomain.LEDGER)); advanceUntilIdle()
        assertFalse(f.state.value.canEdit); f.controller.edit(1); assertNull(f.state.value.editingExpense)
    }

    @Test fun restoreInvalidationIsPrivateAndRejectsActiveQueuedAndDebouncedRequests() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>()
        f.reads.read = { _, _ -> gate.await(); page(99) }
        f.controller.open(); runCurrent()
        f.controller.keyword("queued", false); f.controller.submit(); runCurrent()
        f.controller.keyword("debounced", false)
        val before = f.state.value
        val ticket = requireNotNull(f.coordinator.beginRestore())
        val invalidation = f.controller.invalidateForRestore()
        assertEquals(before, f.state.value)
        // Host publishes one reset across all features after private invalidations.
        f.searchOpen = false; f.state.update { ExpenseSearchUiState() }
        invalidation.cancelPending()
        val restore = async { f.coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {}) }
        gate.complete(Unit); advanceUntilIdle(); restore.await()
        assertEquals(ExpenseSearchUiState(), f.state.value)
        assertEquals(listOf(""), f.keywords())
        f.reads.read = { _, _ -> page(2) }; f.controller.open(); advanceUntilIdle()
        assertEquals(listOf(2L), f.state.value.items.map { it.id }); assertEquals(1L, f.state.value.displayedDataEpoch)
    }

    @Test fun invalidFiltersKeepAppliedCriteriaAndRelativeDatesFollowEnvironment() = runTest {
        val f = Fixture(this); f.controller.open(); advanceUntilIdle(); f.controller.openFilters()
        f.controller.draft(ExpenseSearchFilters(minAmountText = "-1")); f.controller.applyFilters()
        assertNotNull(f.state.value.validationError); assertEquals(ExpenseSearchFilters(), f.state.value.appliedFilters)
        assertEquals(1, f.reads.calls.size)
        f.controller.cancelFilters(); f.controller.replaceFilters(ExpenseSearchFilters(datePreset = SearchDatePreset.TODAY)); advanceUntilIdle()
        f.controller.environmentChanged(LocalDate.of(2026, 10, 1), ZoneId.of("UTC")); advanceUntilIdle()
        assertEquals(1790812800000L, f.reads.calls.last().first.fromInclusiveMillis)
        assertEquals(1790899200000L, f.reads.calls.last().first.untilExclusiveMillis)
    }
}
