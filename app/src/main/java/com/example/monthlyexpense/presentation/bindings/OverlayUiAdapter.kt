package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.presentation.ForegroundUiStore

/** Host-only navigation mapping. Search and Backup route through their own commands first. */
internal class OverlayUiAdapter(private val store: ForegroundUiStore) {
    fun show(overlay: ExpenseOverlay) = store.commit { it.copy(overlay = overlay) }
    fun dismiss() = store.commit {
        val edit = it.overlay as? ExpenseOverlay.EditExpense
        it.copy(overlay = if (edit?.returnToSpecialBudget == true) ExpenseOverlay.SpecialBudgetDetails else null)
    }
}
