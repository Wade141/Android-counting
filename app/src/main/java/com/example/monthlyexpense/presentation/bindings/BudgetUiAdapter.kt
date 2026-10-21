package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.budget.api.BudgetNavigation
import com.example.monthlyexpense.presentation.ForegroundUiStore

internal class BudgetUiAdapter(private val store: ForegroundUiStore) : BudgetNavigation {
    override fun closeIfCurrent(kind: BudgetKind) = store.commitIf(
        { it.overlay == ExpenseOverlay.EditBudget(kind) }, { it.copy(overlay = null) })
}
