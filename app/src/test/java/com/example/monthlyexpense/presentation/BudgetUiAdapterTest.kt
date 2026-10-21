package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.*
import com.example.monthlyexpense.presentation.bindings.BudgetUiAdapter
import org.junit.Assert.*
import org.junit.Test

class BudgetUiAdapterTest {
    @Test fun budgetCompletionOnlyClosesTheMatchingKind() {
        val store = ForegroundUiStore(ExpenseUiState(overlay = ExpenseOverlay.EditBudget(BudgetKind.DAILY)))
        val adapter = BudgetUiAdapter(store)
        assertFalse(adapter.closeIfCurrent(BudgetKind.MONTHLY))
        assertEquals(ExpenseOverlay.EditBudget(BudgetKind.DAILY), store.state.value.overlay)
        assertTrue(adapter.closeIfCurrent(BudgetKind.DAILY)); assertNull(store.state.value.overlay)
        store.commit { it.copy(overlay = ExpenseOverlay.EditExpense(7)) }
        assertFalse(adapter.closeIfCurrent(BudgetKind.DAILY)); assertEquals(ExpenseOverlay.EditExpense(7), store.state.value.overlay)
    }

    @Test fun categoryAndBudgetSlicesPreserveEachOtherAndEditor() {
        val store = ForegroundUiStore(ExpenseUiState(pending = ExpensePendingState(entrySaving = true)))
        store.category.update { it.copy(saving = true, management = CategoryManagementUiState(editor = CategoryEditorTarget.New)) }
        store.budget.update { it.copy(saving = true) }
        assertTrue(store.state.value.pending.entrySaving); assertTrue(store.category.value.saving)
        assertTrue(store.budget.value.saving); assertEquals(CategoryEditorTarget.New, store.category.value.management.editor)
        store.category.update { it.copy(saving = false) }
        assertTrue(store.budget.value.saving); assertTrue(store.state.value.pending.entrySaving)
    }
}
