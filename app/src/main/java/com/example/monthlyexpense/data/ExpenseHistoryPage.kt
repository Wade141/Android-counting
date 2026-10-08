package com.example.monthlyexpense.data

import com.example.monthlyexpense.ExpenseRecord
import java.time.YearMonth

const val HISTORY_PAGE_SIZE = 50

data class HistoryCursor(val spentAt: Long, val id: Long)

data class HistoryMonth(val month: YearMonth, val totalCents: Long, val count: Int)

data class HistoryPage(
    val items: List<ExpenseRecord>,
    val nextCursor: HistoryCursor?,
    val hasMore: Boolean,
    val months: List<HistoryMonth> = emptyList()
)
