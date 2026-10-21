package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.presentation.bindings.RestoreUiAdapter
import com.example.monthlyexpense.refresh.api.RefreshInvalidation
import com.example.monthlyexpense.search.ExpenseSearchUiState
import com.example.monthlyexpense.search.api.SearchInvalidation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreUiAdapterTest {
    @Test fun allHandlesInvalidateBeforeOneResetAndCallbacksRunAfterPublication() = runTest {
        val store = ForegroundUiStore(ExpenseUiState(pending = ExpensePendingState(entrySaving = true, categoryDeleting = true,
            budgetSaving = true, exportPreparing = true), overlay = ExpenseOverlay.ConfirmRestore,
            search = ExpenseSearchUiState(keyword = "旧"), history = HistoryUiState(loadingMore = true), displayedDataEpoch = 7))
        val steps = mutableListOf<String>(); val observed = mutableListOf<ExpenseUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { observed += it; if(it.pending.restoring) steps += "reset" } }
        val hooks = mutableListOf<() -> Unit>({ steps += "editor" }, { steps += "settings" })
        val adapter = RestoreUiAdapter(store, hooks,
            { steps += "search"; SearchInvalidation { steps += "cancel"; assertTrue(store.backup.value.restoring) } },
            { steps += "home"; RefreshInvalidation { steps += "waiters"; assertTrue(store.backup.value.restoring) } },
            { store.history.value.copy(loading = false, loadingMore = false) },
            { AutoBookkeepingUiState(it.autoBookkeepingEnabled, it.weChatEnabled, it.alipayEnabled) })
        hooks += { error("registration must be frozen") }
        adapter.begin(ForegroundSettingsBaseline(true, false, true))
        assertEquals(listOf("search", "home", "editor", "settings", "reset", "waiters", "cancel"), steps)
        assertEquals(2, observed.size); assertEquals(ExpensePendingState(restoring = true), store.state.value.pending)
        assertNull(store.state.value.overlay); assertEquals(ExpenseSearchUiState(), store.search.value)
        assertEquals(7L, store.state.value.displayedDataEpoch); assertFalse(store.history.value.loadingMore)
        adapter.applyPersistedSettings(ForegroundSettingsBaseline(false, true, false))
        assertFalse(store.settings.value.enabled); assertTrue(store.backup.value.restoring); assertEquals(7L, store.state.value.displayedDataEpoch)
    }
}
