package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.ExpenseUiState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.entry.api.ExpenseEditorState
import com.example.monthlyexpense.categories.api.CategoryOperationState
import com.example.monthlyexpense.budget.api.BudgetOperationState
import com.example.monthlyexpense.backup.api.BackupOperationState
import com.example.monthlyexpense.presentation.contracts.ReadState
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.search.ExpenseSearchUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One physical state; slices are views, never additional MutableStateFlows. */
internal class ForegroundUiStore(initial: ExpenseUiState = ExpenseUiState()) {
    private val mutableState = MutableStateFlow(initial)
    val state = mutableState.asStateFlow()

    val settings: SliceState<AutoBookkeepingUiState> = slice({ it.autoBookkeeping }) { root, value ->
        root.copy(autoBookkeeping = value)
    }
    val history: SliceState<HistoryUiState> = slice({ it.history }) { root, value -> root.copy(history = value) }
    val search: SliceState<ExpenseSearchUiState> = slice({ it.search }) { root, value -> root.copy(search = value) }
    val editor: SliceState<ExpenseEditorState> = slice({ ExpenseEditorState(it.pending.entrySaving,
        it.pending.expenseEditSaving, it.categoryCorrection) }) { root, value -> root.copy(
        pending = root.pending.copy(entrySaving = value.entrySaving, expenseEditSaving = value.expenseEditSaving),
        categoryCorrection = value.categoryCorrection) }
    val categories: ReadState<List<ExpenseCategory>> = object : ReadState<List<ExpenseCategory>> {
        override val value get() = state.value.categories
    }
    val displayedEpoch: ReadState<Long> = object : ReadState<Long> {
        override val value get() = state.value.displayedDataEpoch
    }
    val historyLoaded: ReadState<Boolean> = object : ReadState<Boolean> {
        override val value get() = state.value.history.initialLoadComplete
    }
    val category: SliceState<CategoryOperationState> = slice({ CategoryOperationState(it.categoryManagement,
        it.pending.categorySaving, it.pending.categoryDeleting) }) { root, value -> root.copy(
        categoryManagement = value.management,
        pending = root.pending.copy(categorySaving = value.saving, categoryDeleting = value.deleting)) }
    val budget: SliceState<BudgetOperationState> = slice({ BudgetOperationState(it.pending.budgetSaving) }) { root, value ->
        root.copy(pending = root.pending.copy(budgetSaving = value.saving))
    }
    val backup: SliceState<BackupOperationState> = slice({ BackupOperationState(it.pending.exportPreparing, it.pending.restoring) }) { root, value ->
        root.copy(pending = root.pending.copy(exportPreparing = value.exportPreparing, restoring = value.restoring))
    }

    /** Only composition adapters may perform named cross-slice commits. */
    fun commit(transform: (ExpenseUiState) -> ExpenseUiState) = mutableState.update(transform)

    /** Conditional atomic publication for composition adapters. Both functions must be pure.
     * Returns whether this call committed, even if a synchronous observer immediately changes state. */
    fun commitIf(predicate: (ExpenseUiState) -> Boolean, transform: (ExpenseUiState) -> ExpenseUiState): Boolean {
        while (true) {
            val before = mutableState.value
            if (!predicate(before)) return false
            if (mutableState.compareAndSet(before, transform(before))) return true
        }
    }

    private fun <S> slice(read: (ExpenseUiState) -> S, replace: (ExpenseUiState, S) -> ExpenseUiState): SliceState<S> =
        object : SliceState<S> {
            override val value: S get() = read(mutableState.value)
            override fun update(transform: (S) -> S) {
                mutableState.update { root -> replace(root, transform(read(root))) }
            }
        }
}
