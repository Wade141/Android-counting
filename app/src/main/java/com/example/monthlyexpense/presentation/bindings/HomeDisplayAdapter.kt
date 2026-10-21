package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.presentation.ForegroundUiStore
import com.example.monthlyexpense.refresh.api.HomeDisplayPort

internal class HomeDisplayAdapter(private val store: ForegroundUiStore) : HomeDisplayPort {
    override val initialLoadComplete get() = store.state.value.initialLoadComplete
    override val notificationState get() = store.state.value.notificationState
    override fun updateNotificationState(state: NotificationListenerConnectionState) = store.commit { it.copy(notificationState = state) }
}
