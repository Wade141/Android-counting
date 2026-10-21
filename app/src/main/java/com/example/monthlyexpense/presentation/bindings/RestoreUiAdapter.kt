package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.ExpensePendingState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.backup.api.BackupOperationState
import com.example.monthlyexpense.backup.api.BackupSettingsCommit
import com.example.monthlyexpense.backup.api.RestoreUiBoundary
import com.example.monthlyexpense.budget.api.BudgetOperationState
import com.example.monthlyexpense.categories.api.CategoryOperationState
import com.example.monthlyexpense.entry.api.ExpenseEditorState
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.presentation.ForegroundUiStore
import com.example.monthlyexpense.refresh.api.RefreshInvalidation
import com.example.monthlyexpense.search.ExpenseSearchUiState
import com.example.monthlyexpense.search.api.SearchInvalidation

/** Fixed composition boundary, never a service locator. No IO/coordinator or dynamic registration.
 * All hooks are synchronous: invalidate without publication/callbacks, then one pure aggregate
 * patch, then detached completion. Feature API defaults and pure selectors own reset values. */
internal class RestoreUiAdapter(
    private val store: ForegroundUiStore,
    invalidations: List<() -> Unit>,
    private val invalidateSearch: () -> SearchInvalidation,
    private val invalidateRefresh: () -> RefreshInvalidation,
    private val historyReset: () -> HistoryUiState,
    private val settingsReset: (ForegroundSettingsBaseline) -> AutoBookkeepingUiState,
) : RestoreUiBoundary, BackupSettingsCommit {
    private val invalidations = invalidations.toList()

    override fun begin(baseline: ForegroundSettingsBaseline) {
        val search = invalidateSearch()
        val refresh = invalidateRefresh()
        invalidations.forEach { it() }
        val editor = ExpenseEditorState()
        val category = CategoryOperationState()
        val budget = BudgetOperationState()
        val backup = BackupOperationState(restoring = true)
        val history = historyReset()
        val settings = settingsReset(baseline)
        store.commit { it.copy(autoBookkeeping = settings, pending = ExpensePendingState(
            entrySaving = editor.entrySaving, expenseEditSaving = editor.expenseEditSaving,
            categorySaving = category.saving, categoryDeleting = category.deleting,
            budgetSaving = budget.saving, exportPreparing = backup.exportPreparing, restoring = backup.restoring),
            overlay = null, categoryManagement = category.management, categoryCorrection = editor.categoryCorrection,
            search = ExpenseSearchUiState(), history = history) }
        refresh.completeStale()
        search.cancelPending()
    }

    override fun applyPersistedSettings(baseline: ForegroundSettingsBaseline) {
        val settings = settingsReset(baseline)
        store.commit { it.copy(autoBookkeeping = settings) }
    }
}
