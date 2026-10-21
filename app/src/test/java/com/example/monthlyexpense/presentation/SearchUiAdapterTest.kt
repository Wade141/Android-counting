package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.*
import com.example.monthlyexpense.presentation.bindings.SearchUiAdapter
import com.example.monthlyexpense.search.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchUiAdapterTest {
    private fun snapshot() = SearchEditSnapshot(ExpenseRecord(7, 100, ExpenseCategory("FOOD", "饮食", 0, true, 0),
        "茶", "", 1000, ExpenseSource.MANUAL, null), 3)

    @Test fun editorHandoffAndReturnEachPublishOneCoherentRoot() = runTest {
        val initial = ExpenseUiState(search = ExpenseSearchUiState(keyword = "茶", displayedDataEpoch = 3),
            pending = ExpensePendingState(budgetSaving = true))
        val store = ForegroundUiStore(initial); val ui = SearchUiAdapter(store)
        val samples = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { samples += it } }
        val snapshot = snapshot(); ui.open(snapshot)
        assertEquals(2, samples.size)
        assertEquals(ExpenseOverlay.EditExpense(7), samples.last().overlay)
        assertEquals(snapshot, samples.last().search.editingExpense); assertEquals(initial.pending, samples.last().pending)
        assertTrue(ui.isVisible())
        ui.returnToSearch()
        assertEquals(3, samples.size); assertEquals(ExpenseOverlay.Search, samples.last().overlay)
        assertNull(samples.last().search.editingExpense); assertEquals("茶", samples.last().search.keyword)
    }

    @Test fun closeIsConditionalAndDoesNotCloseAnUnrelatedOverlay() {
        val store = ForegroundUiStore(ExpenseUiState(overlay = ExpenseOverlay.EditBudget(BudgetKind.DAILY),
            search = ExpenseSearchUiState(keyword = "茶", isComposing = true)))
        val ui = SearchUiAdapter(store); val before = store.state.value
        assertFalse(ui.closeSearchIfCurrent()); assertEquals(before, store.state.value)
        ui.openSearch(); assertFalse(store.search.value.isComposing)
        assertTrue(ui.closeSearchIfCurrent()); assertNull(store.state.value.overlay)
        assertEquals("茶", store.search.value.keyword); assertFalse(ui.isVisible())
    }

    @Test fun closeReportsCommittedSuccessEvenWhenSynchronousObserverOpensAnotherOverlay() = runTest {
        val store = ForegroundUiStore(ExpenseUiState(overlay = ExpenseOverlay.Search))
        val ui = SearchUiAdapter(store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.state.collect { if (it.overlay == null) store.commit { root -> root.copy(overlay = ExpenseOverlay.Menu) } }
        }
        assertTrue(ui.closeSearchIfCurrent())
        assertEquals(ExpenseOverlay.Menu, store.state.value.overlay)
    }
}
