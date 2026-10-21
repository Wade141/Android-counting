package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.presentation.ForegroundUiStore
import com.example.monthlyexpense.refresh.api.HomeSnapshotCommit

internal class HomeSnapshotAdapter(private val store: ForegroundUiStore) : HomeSnapshotCommit {
    override fun apply(snapshot: ExpenseHomeSnapshot, history: HistoryUiState, settings: AutoBookkeepingUiState,
        notificationState: NotificationListenerConnectionState, displayedDataEpoch: Long) = store.commit {
        it.copy(expenses = snapshot.expenses, history = history, categories = snapshot.categories,
            monthlyBudgetCents = snapshot.monthlyBudgetCents, dailyBudgetCents = snapshot.dailyBudgetCents,
            todayTotalCents = snapshot.todayTotalCents, notificationState = notificationState,
            autoBookkeeping = settings, displayedDataEpoch = displayedDataEpoch, initialLoadComplete = true)
    }
}
