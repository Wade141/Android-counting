package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.CategoryCorrection
import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.entry.api.*
import com.example.monthlyexpense.presentation.ForegroundUiStore

/** Pure snapshot/patch mapping. Inject individual capabilities, never this adapter as a container. */
internal class EditorUiAdapter(private val store: ForegroundUiStore) : ExpenseEditContextReader,
    ExpenseHistoryMembership, EditorNavigation, EditorCommitPort, ExpenseMutationEffects {
    override fun read(id: Long): ExpenseEditContext {
        val state = store.state.value
        val searchEdit = state.search.editingExpense?.takeIf { it.expense.id == id }
        val overlay = state.overlay as? ExpenseOverlay.EditExpense
        return ExpenseEditContext(searchEdit?.expense ?: (state.expenses + state.history.items).find { it.id == id },
            searchEdit?.epoch ?: state.displayedDataEpoch, searchEdit != null,
            overlay?.expenseId == id && overlay.returnToSpecialBudget)
    }
    override fun contains(id: Long) = store.state.value.history.items.any { it.id == id }
    override fun closeEntryIfCurrent() = store.commitIf({ it.overlay == ExpenseOverlay.Entry }) { it.copy(overlay = null) }
    override fun applySavedEdit(target: SavedEditTarget, correction: CategoryCorrection?) {
        store.commit { state ->
            val edit = state.overlay as? ExpenseOverlay.EditExpense
            val updated = if (edit?.expenseId == target.expenseId) {
                if (target.fromSearch) state.copy(overlay = ExpenseOverlay.Search, search = state.search.copy(editingExpense = null))
                else state.copy(overlay = if (edit.returnToSpecialBudget) ExpenseOverlay.SpecialBudgetDetails else null)
            } else state
            if (correction != null) updated.copy(categoryCorrection = correction) else updated
        }
    }
    override fun removeDisplayed(id: Long) {
        store.commit { state -> state.copy(expenses = state.expenses.filterNot { it.id == id },
            history = state.history.copy(items = state.history.items.filterNot { it.id == id })) }
    }
}
