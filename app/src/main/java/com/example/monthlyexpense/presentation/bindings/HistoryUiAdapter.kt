package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.history.api.HistoryNavigation
import com.example.monthlyexpense.presentation.ForegroundUiStore

internal class HistoryUiAdapter(private val store: ForegroundUiStore) : HistoryNavigation {
    override val isOpen get() = store.state.value.overlay == ExpenseOverlay.History
    override fun open(history: HistoryUiState) = store.commit { it.copy(overlay = ExpenseOverlay.History, history = history) }
}
