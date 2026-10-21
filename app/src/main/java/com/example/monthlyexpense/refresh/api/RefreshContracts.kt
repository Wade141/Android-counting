package com.example.monthlyexpense.refresh.api

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.refresh.RefreshRequest
import com.example.monthlyexpense.refresh.RefreshResult
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.ZoneId

/** Same Repository signatures/defaults; errors/cancellation propagate; no UI or lock side effects. */
internal interface HomeReadPort {
    suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot
    suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot = loadHomeSnapshot(now, zoneId)
    suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: ZoneId, previous: ExpenseHomeSnapshot,
        domains: Set<DataDomain>): ExpenseHomeSnapshot = loadHomeSnapshot(now, zoneId)
}
/** Only these display values are exposed to Home. Synchronous reads/update; no IO. */
internal interface HomeDisplayPort {
    val initialLoadComplete: Boolean
    val notificationState: NotificationListenerConnectionState
    fun updateNotificationState(state: NotificationListenerConnectionState)
}
internal interface HomeSnapshotCommit {
    /** One publication after current-ticket checks; no business logic or suspension. */
    fun apply(snapshot: ExpenseHomeSnapshot, history: HistoryUiState, settings: AutoBookkeepingUiState,
        notificationState: NotificationListenerConnectionState, displayedDataEpoch: Long)
}
internal interface RefreshCommands {
    /** Schedules only, no completion acknowledgement. Preserves external notification priority. */
    fun request(request: RefreshRequest, reportPageFailure: Boolean, notificationStateIsExternal: Boolean)
}
internal interface MutationRefreshPort {
    /** Waits for display commit/staleness/read failure; cancellation propagates. Does not own writer pending. */
    suspend fun afterMutation(scope: RefreshScope, domains: Set<DataDomain>): RefreshResult
}
internal interface RestoreRefreshPort {
    /** ONLY inside runRestore.refreshAfterRestore. No queue/runRead. Errors/cancellation propagate. */
    suspend fun refreshWithinRestore()
}
internal fun interface RefreshInvalidation {
    /** Complete detached waiters as STALE synchronously AFTER the atomic restore reset. */
    fun completeStale()
}
