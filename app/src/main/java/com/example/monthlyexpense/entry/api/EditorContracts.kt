package com.example.monthlyexpense.entry.api

import com.example.monthlyexpense.CategoryCorrection
import com.example.monthlyexpense.ExpenseEditInput
import com.example.monthlyexpense.ExpenseRecord
import com.example.monthlyexpense.NewExpenseInput
import java.time.LocalDate
import java.time.LocalTime

/** Editor's logical ownership; the physical source remains the aggregate presentation store. */
internal data class ExpenseEditorState(
    val entrySaving: Boolean = false,
    val expenseEditSaving: Boolean = false,
    val categoryCorrection: CategoryCorrection? = null,
)

/** Suspend writes preserve repository overloads, Boolean rejection and cancellation/exception semantics.
 * Adapters delegate once without caching, refreshing, acquiring tickets, or changing dispatchers. */
internal interface ExpenseWritePort {
    suspend fun addExpense(amountCents: Long, categoryKey: String, name: String, note: String): Boolean
    suspend fun addExpense(input: NewExpenseInput): Boolean
    suspend fun updateExpense(id: Long, name: String, note: String, categoryKey: String,
        date: LocalDate? = null, time: LocalTime? = null): Boolean
    suspend fun updateExpense(id: Long, input: ExpenseEditInput): Boolean
    suspend fun deleteExpense(id: Long)
}

internal data class ExpenseEditContext(val record: ExpenseRecord?, val epoch: Long,
    val fromSearch: Boolean, val returnToSpecialBudget: Boolean)

internal interface ExpenseEditContextReader {
    /** Synchronous, side-effect-free snapshot; search edit wins, then home/history. Missing record is
     * permitted. Called with ticket capture in the shared admission boundary, never after suspension. */
    fun read(id: Long): ExpenseEditContext
}

/** Read-only history membership for choosing refresh scope, no paging or refresh capability. */
internal fun interface ExpenseHistoryMembership { fun contains(id: Long): Boolean }

internal interface EditorNavigation {
    /** Atomic synchronous close; false means current overlay changed, never retry/close another target. */
    fun closeEntryIfCurrent(): Boolean
}

internal data class SavedEditTarget(val expenseId: Long, val fromSearch: Boolean)

internal fun interface EditorCommitPort {
    /** Single synchronous publication of matching-ID navigation, search snapshot clear and correction.
     * Non-null correction applies even if the target changed; null keeps existing correction. The
     * current matching overlay determines special-budget return. Caller owns ticket/generation checks. */
    fun applySavedEdit(target: SavedEditTarget, correction: CategoryCorrection?)
}

internal fun interface ExpenseMutationEffects {
    /** Single synchronous home/history removal, without changing search results or aggregate totals.
     * Called only after current successful persistence; no IO or controller calls. */
    fun removeDisplayed(id: Long)
}
