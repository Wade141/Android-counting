package com.example.monthlyexpense.presentation.bindings

import com.example.monthlyexpense.ExpenseOverlay
import com.example.monthlyexpense.presentation.ForegroundUiStore
import com.example.monthlyexpense.search.SearchEditSnapshot
import com.example.monthlyexpense.search.api.SearchEditorNavigation
import com.example.monthlyexpense.search.api.SearchNavigation

/** Pure presentation mapping. Features receive each capability separately, never this adapter. */
internal class SearchUiAdapter(private val store: ForegroundUiStore) : SearchNavigation, SearchEditorNavigation {
    /** Includes an editor opened from search so background refresh can retain its captured record. */
    fun isVisible(): Boolean {
        val state = store.state.value
        return state.overlay == ExpenseOverlay.Search || state.search.editingExpense != null
    }
    override fun openSearch() = store.commit {
        it.copy(overlay = ExpenseOverlay.Search, search = it.search.copy(editingExpense = null, isComposing = false))
    }
    override fun closeSearchIfCurrent(): Boolean = store.commitIf({ it.overlay == ExpenseOverlay.Search }) {
        it.copy(overlay = null, search = it.search.copy(filterDraft = null, editingExpense = null, isComposing = false))
    }
    override fun open(snapshot: SearchEditSnapshot) = store.commit {
        it.copy(overlay = ExpenseOverlay.EditExpense(snapshot.expense.id), search = it.search.copy(editingExpense = snapshot))
    }
    override fun returnToSearch() = store.commit {
        it.copy(overlay = ExpenseOverlay.Search, search = it.search.copy(editingExpense = null))
    }
}
