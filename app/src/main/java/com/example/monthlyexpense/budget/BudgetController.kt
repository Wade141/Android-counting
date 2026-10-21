package com.example.monthlyexpense.budget

import com.example.monthlyexpense.BudgetKind
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.budget.api.*
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.presentation.ForegroundMutationRunner
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth

/** Owns budget validation and one pending/generation across both kinds, not displayed budget values.
 * Commands use the existing UI thread; all admission/cleanup uses the shared host lock. */
internal class BudgetController(
    private val state: SliceState<BudgetOperationState>,
    private val writes: BudgetWritePort,
    private val displayedEpoch: ReadState<Long>,
    private val navigation: BudgetNavigation,
    private val runner: ForegroundMutationRunner,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val admissionLock: Any,
    private val today: () -> LocalDate,
) {
    private var generation = 0L
    private val slot = object : MutationSlot {
        override fun admit(): Long? = synchronized(admissionLock) {
            if (state.value.saving) return@synchronized null
            val admitted = ++generation
            state.update { it.copy(saving = true) }
            admitted
        }
        override fun isCurrent(generation: Long) = synchronized(admissionLock) { this@BudgetController.generation == generation }
        override fun finish(generation: Long) = synchronized(admissionLock) {
            if (this@BudgetController.generation == generation) state.update { it.copy(saving = false) }
        }
    }

    fun setBudget(kind: BudgetKind, amountCents: Long) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        runner.launch(ticket, slot,
            validationMessage = { UiMessage("预算金额无效").takeUnless { amountCents in 0..MoneyLimits.MAX_CENTS } },
            mutation = {
                when (kind) {
                    // Preserve sampling at execution, after persistence admission, including a queued month rollover.
                    BudgetKind.MONTHLY -> writes.setMonthlyBudget(YearMonth.from(today()), amountCents)
                    BudgetKind.DAILY -> { writes.setDailyBudget(amountCents); true }
                }
            },
            translate = { MutationResult(it, if (it) null else UiMessage("预算金额无效")) },
            applyCommitted = { navigation.closeIfCurrent(kind); Unit },
            refreshScope = RefreshScope.HOME, refreshDomains = setOf(DataDomain.SETTINGS),
            failureMessage = UiMessage("预算保存失败，请重试"))
    }

    /** Invalidate only; composition owns the single restore publication. No IO/callbacks/state writes. */
    fun invalidateForRestore() = synchronized(admissionLock) { generation++; Unit }
}
