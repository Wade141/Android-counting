package com.example.monthlyexpense.search

import com.example.monthlyexpense.ExpenseRecord

data class SearchEditSnapshot(val expense: ExpenseRecord, val epoch: Long)

data class ExpenseSearchUiState(
    val keyword: String = "",
    val isComposing: Boolean = false,
    val appliedFilters: ExpenseSearchFilters = ExpenseSearchFilters(),
    val filterDraft: ExpenseSearchFilters? = null,
    val validationError: String? = null,
    val items: List<ExpenseRecord> = emptyList(),
    val summary: ExpenseSearchSummary? = null,
    val nextCursor: ExpenseSearchCursor? = null,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val updating: Boolean = false,
    val error: String? = null,
    val failedCursor: ExpenseSearchCursor? = null,
    val dirty: Boolean = true,
    val displayedDataEpoch: Long = -1,
    val canEdit: Boolean = false,
    val editingExpense: SearchEditSnapshot? = null
)
