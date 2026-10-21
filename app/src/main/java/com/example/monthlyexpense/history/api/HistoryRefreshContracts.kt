package com.example.monthlyexpense.history.api

import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth

/** Only History interprets generation, selected month and loading policy. */
internal data class HistoryRefreshPlan(val generation: Long?, val loadFirstPage: Boolean, val previous: HistoryUiState)
internal sealed interface HistoryPageRead {
    data object NotRequested : HistoryPageRead
    data class Loaded(val page: HistoryPage) : HistoryPageRead
    data class Failed(val error: Exception) : HistoryPageRead
}
internal interface HistoryRefreshPort {
    /** Synchronously reserves a generation and publishes loading under caller's current-ticket commit. */
    fun prepareRefresh(requestedScope: RefreshScope, refreshDate: LocalDate, previousMonth: YearMonth?): HistoryRefreshPlan
    /** Caller owns persistence lock. No runRead; cancellation propagates, ordinary errors become Failed. */
    suspend fun readForRefresh(plan: HistoryRefreshPlan): HistoryPageRead
    /** No publication; returns current history for stale plans. Commit result without suspension. */
    fun reduceRefresh(read: HistoryPageRead, plan: HistoryRefreshPlan): HistoryUiState
    /** Synchronous matching-generation failure update. Stale plan is a no-op; no IO. */
    fun failRefresh(plan: HistoryRefreshPlan)
}
