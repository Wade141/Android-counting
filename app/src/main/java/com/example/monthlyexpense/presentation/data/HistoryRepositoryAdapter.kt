package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.history.api.HistoryReadPort
import java.time.YearMonth

internal class HistoryRepositoryAdapter(private val repository: ExpenseRepositoryContract) : HistoryReadPort {
    override suspend fun loadHistoryPage(cursor: HistoryCursor?, limit: Int, month: YearMonth?) = repository.loadHistoryPage(cursor, limit, month)
}
