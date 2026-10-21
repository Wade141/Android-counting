package com.example.monthlyexpense.history.api

import com.example.monthlyexpense.HistoryUiState
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import java.time.YearMonth

/** Suspend read only; errors/cancellation propagate. Caller owns lock admission. */
internal interface HistoryReadPort {
    suspend fun loadHistoryPage(cursor: HistoryCursor?, limit: Int = HISTORY_PAGE_SIZE, month: YearMonth? = null): HistoryPage
}
/** Synchronous, IO-free. Publishes the supplied history and overlay together. */
internal interface HistoryNavigation {
    val isOpen: Boolean
    fun open(history: HistoryUiState)
}
