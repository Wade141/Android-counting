package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.CategoryManagementUiState
import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.ExpensePendingState
import com.example.monthlyexpense.ExpenseUiState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.presentation.bindings.BackupUiAdapter
import com.example.monthlyexpense.presentation.bindings.HomeSnapshotAdapter
import com.example.monthlyexpense.presentation.bindings.RestoreUiAdapter
import com.example.monthlyexpense.search.ExpenseSearchUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundUiStoreTest {
    @Test fun sliceUpdatePreservesOtherFeatures() {
        val initial = ExpenseUiState(search = ExpenseSearchUiState(keyword = "午饭"),
            autoBookkeeping = AutoBookkeepingUiState(enabled = true),
            pending = ExpensePendingState(budgetSaving = true))
        val store = ForegroundUiStore(initial)
        store.history.update { it.copy(loading = true) }
        assertEquals(initial.copy(history = initial.history.copy(loading = true)), store.state.value)
        store.settings.update { it.copy(weChatEnabled = false) }
        assertTrue(store.history.value.loading)
        assertEquals("午饭", store.search.value.keyword)
        assertTrue(store.state.value.pending.budgetSaving)
        assertFalse(store.settings.value.weChatEnabled)
    }

    @Test fun snapshotAndEpochPublishTogether() = runTest {
        val store = ForegroundUiStore()
        val observed = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { observed += it } }
        val snapshot = ExpenseHomeSnapshot(emptyList(), emptyList(), 400L, 20L, 7L, true, false, true)
        val history = HistoryUiState(initialLoadComplete = true)
        HomeSnapshotAdapter(store).apply(snapshot, history, AutoBookkeepingUiState(enabled = true),
            NotificationListenerConnectionState.CONNECTED, 3L)
        assertEquals(2, observed.size)
        assertEquals(3L, observed.last().displayedDataEpoch)
        assertEquals(400L, observed.last().monthlyBudgetCents)
        assertEquals(history, observed.last().history)
        assertTrue(observed.last().initialLoadComplete)
    }

    @Test fun restoreBatchDoesNotExposePartiallyResetState() = runTest {
        val store = ForegroundUiStore(ExpenseUiState(
            search = ExpenseSearchUiState(keyword = "旧查询"),
            history = HistoryUiState(loading = true, loadingMore = true),
            overlay = ExpenseOverlay.ConfirmRestore,
            categoryManagement = CategoryManagementUiState(deleteTargetKey = "FOOD"),
            pending = ExpensePendingState(entrySaving = true, exportPreparing = true),
            displayedDataEpoch = 5L))
        val observed = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { observed += it } }
        val settings = AutoBookkeepingUiState(enabled = true, weChatEnabled = false)
        RestoreUiAdapter(store, emptyList(),
            { com.example.monthlyexpense.search.api.SearchInvalidation {} },
            { com.example.monthlyexpense.refresh.api.RefreshInvalidation {} },
            { store.history.value.copy(loading = false, loadingMore = false) }, { settings })
            .begin(com.example.monthlyexpense.data.ForegroundSettingsBaseline(true, false, true))
        assertEquals(2, observed.size)
        val reset = observed.last()
        assertEquals(settings, reset.autoBookkeeping)
        assertEquals(ExpenseSearchUiState(), reset.search)
        assertEquals(ExpensePendingState(restoring = true), reset.pending)
        assertEquals(CategoryManagementUiState(), reset.categoryManagement)
        assertNull(reset.overlay)
        assertFalse(reset.history.loading)
        assertFalse(reset.history.loadingMore)
        assertEquals(5L, reset.displayedDataEpoch)
    }

    @Test fun obsoleteRestoreConfirmationCannotCloseNewOverlay() {
        val store = ForegroundUiStore()
        val navigation = BackupUiAdapter(store)
        navigation.showRestoreConfirmation()
        assertTrue(navigation.closeRestoreConfirmationIfCurrent())
        store.commit { it.copy(overlay = ExpenseOverlay.EditBudget(com.example.monthlyexpense.BudgetKind.DAILY)) }
        assertFalse(navigation.closeRestoreConfirmationIfCurrent())
        assertEquals(ExpenseOverlay.EditBudget(com.example.monthlyexpense.BudgetKind.DAILY), store.state.value.overlay)
    }
}
