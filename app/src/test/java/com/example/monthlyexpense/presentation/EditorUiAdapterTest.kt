package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.*
import com.example.monthlyexpense.entry.api.*
import com.example.monthlyexpense.presentation.bindings.EditorUiAdapter
import com.example.monthlyexpense.search.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorUiAdapterTest {
    private val row = ExpenseRecord(7, 100, ExpenseCategory("FOOD", "饮食", 0, true, 0),
        "茶", "", 1000, ExpenseSource.MANUAL, "商家")
    private val correction = CategoryCorrection("FOOD", "商家", "新名称", 3)

    @Test fun savedSearchEditPublishesNavigationSnapshotAndCorrectionTogether() = runTest {
        val store = ForegroundUiStore(ExpenseUiState(overlay = ExpenseOverlay.EditExpense(7),
            search = ExpenseSearchUiState(editingExpense = SearchEditSnapshot(row, 3))))
        val observed = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { observed += it } }
        EditorUiAdapter(store).applySavedEdit(SavedEditTarget(7, true), correction)
        assertEquals(2, observed.size)
        assertEquals(ExpenseOverlay.Search, observed.last().overlay)
        assertNull(observed.last().search.editingExpense); assertEquals(correction, observed.last().categoryCorrection)
    }

    @Test fun changedTargetKeepsNavigationButStillAcceptsCorrectionAndNullPreservesIt() {
        for (overlay in listOf(ExpenseOverlay.EditExpense(8), ExpenseOverlay.EditBudget(BudgetKind.MONTHLY))) {
            val snapshot = SearchEditSnapshot(row, 3)
            val store = ForegroundUiStore(ExpenseUiState(overlay = overlay,
                search = ExpenseSearchUiState(editingExpense = snapshot)))
            val adapter = EditorUiAdapter(store)
            adapter.applySavedEdit(SavedEditTarget(7, true), correction)
            assertEquals(overlay, store.state.value.overlay); assertEquals(snapshot, store.search.value.editingExpense)
            assertEquals(correction, store.state.value.categoryCorrection)
            adapter.applySavedEdit(SavedEditTarget(7, false), null)
            assertEquals(correction, store.state.value.categoryCorrection)
        }
    }

    @Test fun matchingIdUsesCurrentSpecialReturnFlagAndEntryCloseIsConditional() {
        val store = ForegroundUiStore(ExpenseUiState(overlay = ExpenseOverlay.EditExpense(7, true)))
        val adapter = EditorUiAdapter(store)
        adapter.applySavedEdit(SavedEditTarget(7, false), null)
        assertEquals(ExpenseOverlay.SpecialBudgetDetails, store.state.value.overlay)
        assertFalse(adapter.closeEntryIfCurrent())
        store.commit { it.copy(overlay = ExpenseOverlay.Entry) }
        assertTrue(adapter.closeEntryIfCurrent()); assertNull(store.state.value.overlay)
    }

    @Test fun contextPrefersSearchSnapshotAndDeletePatchesOnlyHomeAndHistoryOnce() = runTest {
        val snapshot = SearchEditSnapshot(row.copy(name = "搜索快照"), 3)
        val store = ForegroundUiStore(ExpenseUiState(expenses = listOf(row), history = HistoryUiState(items = listOf(row)),
            displayedDataEpoch = 4, search = ExpenseSearchUiState(items = listOf(row), editingExpense = snapshot)))
        val adapter = EditorUiAdapter(store)
        assertEquals(snapshot.expense, adapter.read(7).record); assertEquals(3L, adapter.read(7).epoch)
        assertTrue(adapter.read(7).fromSearch); assertNull(adapter.read(8).record); assertEquals(4L, adapter.read(8).epoch)
        val observed = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { observed += it } }
        adapter.removeDisplayed(7)
        assertEquals(2, observed.size); assertTrue(store.state.value.expenses.isEmpty()); assertTrue(store.history.value.items.isEmpty())
        assertEquals(listOf(row), store.search.value.items); assertEquals(snapshot, store.search.value.editingExpense)
    }

    @Test fun editorSliceUpdatesOnlyItsOwnedFields() {
        val store = ForegroundUiStore(ExpenseUiState(pending = ExpensePendingState(budgetSaving = true)))
        store.editor.update { it.copy(entrySaving = true, categoryCorrection = correction) }
        assertTrue(store.state.value.pending.budgetSaving); assertTrue(store.editor.value.entrySaving)
        assertFalse(store.editor.value.expenseEditSaving); assertEquals(correction, store.state.value.categoryCorrection)
    }
}
