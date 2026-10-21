package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.*
import com.example.monthlyexpense.presentation.bindings.OverlayUiAdapter
import com.example.monthlyexpense.presentation.bindings.BackupUiAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayUiAdapterTest {
    @Test fun navigationPreservesOtherSlicesAndSpecialBudgetReturn() {
        val store = ForegroundUiStore(ExpenseUiState(pending = ExpensePendingState(budgetSaving = true)))
        val nav = OverlayUiAdapter(store)
        nav.show(ExpenseOverlay.EditExpense(9, returnToSpecialBudget = true))
        nav.dismiss()
        assertEquals(ExpenseOverlay.SpecialBudgetDetails, store.state.value.overlay)
        assertTrue(store.budget.value.saving)
        nav.show(ExpenseOverlay.EditExpense(10)); nav.dismiss()
        assertNull(store.state.value.overlay)
    }

    @Test fun backupCloseReturnsItsCommitResultEvenIfObserverImmediatelyOpensAnotherOverlay() = runTest {
        val store = ForegroundUiStore()
        val backup = BackupUiAdapter(store)
        backup.showRestoreConfirmation()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.state.collect { if (it.overlay == null) OverlayUiAdapter(store).show(ExpenseOverlay.Search) }
        }
        assertTrue(backup.closeRestoreConfirmationIfCurrent())
        assertEquals(ExpenseOverlay.Search, store.state.value.overlay)
        assertFalse(backup.closeRestoreConfirmationIfCurrent())
    }
}
