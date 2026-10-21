package com.example.monthlyexpense.search.api

import com.example.monthlyexpense.search.ExpenseSearchCursor
import com.example.monthlyexpense.search.ExpenseSearchPage
import com.example.monthlyexpense.search.ExpenseSearchQuery
import com.example.monthlyexpense.search.SearchEditSnapshot

/** Repository signature/default unchanged. Suspend read only; errors/cancellation propagate.
 * Caller owns coordinator admission; adapter adds no cache, dispatcher, lock, or UI side effects. */
internal interface SearchReadPort {
    suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int = 50): ExpenseSearchPage
}

/** Synchronous UI-only operations; no queries. Each navigation and associated slice patch is atomic. */
internal interface SearchNavigation {
    /** Opens search, clears the editing handoff and abandoned composition; retains keyword/filters. */
    fun openSearch()
    /** False if search is no longer the active overlay; leaves that unrelated target unchanged. */
    fun closeSearchIfCurrent(): Boolean
}

internal interface SearchEditorNavigation {
    /** Synchronously publishes this immutable record+epoch snapshot and its matching editor together. */
    fun open(snapshot: SearchEditSnapshot)
    /** Synchronous user return only; clears handoff and opens search together, never starts IO.
     * Async save completion uses the editor's own conditional commit (T5), not this method. */
    fun returnToSearch()
}

internal fun interface SearchInvalidation {
    /** Cancel detached debounce only AFTER all generations and aggregate restore state are reset. */
    fun cancelPending()
}
