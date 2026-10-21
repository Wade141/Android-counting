package com.example.monthlyexpense.budget.api

import com.example.monthlyexpense.BudgetKind
import java.time.YearMonth

/** Budget owns only its pending bit; amounts come from authoritative home snapshots. */
internal data class BudgetOperationState(val saving: Boolean = false)

/** Suspend, pure repository delegation. false means monthly validation/storage rejection; daily
 * retains Unit. Exceptions/cancellation propagate. No tickets, cache, refresh or added dispatcher. */
internal interface BudgetWritePort {
    suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean
    suspend fun setDailyBudget(amountCents: Long)
}

internal fun interface BudgetNavigation {
    /** Synchronous atomic close only for the currently matching kind. false is a changed target,
     * not persistence failure; do not retry. No IO, navigation callbacks, or business decisions. */
    fun closeIfCurrent(kind: BudgetKind): Boolean
}
