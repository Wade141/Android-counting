package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.search.ExpenseSearchCursor
import com.example.monthlyexpense.search.ExpenseSearchQuery
import com.example.monthlyexpense.search.api.SearchReadPort

internal class SearchRepositoryAdapter(private val repository: ExpenseRepositoryContract) : SearchReadPort {
    override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int) =
        repository.loadSearchPage(query, cursor, limit)
}
