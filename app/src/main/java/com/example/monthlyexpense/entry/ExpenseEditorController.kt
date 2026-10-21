package com.example.monthlyexpense.entry

import com.example.monthlyexpense.CategoryCorrection
import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseEditInput
import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.NewExpenseInput
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.entry.api.*
import com.example.monthlyexpense.presentation.ForegroundMutationRunner
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.RefreshScope

/** Owns expense validation, overload selection, correction decisions and two mutation generations.
 * All command admission shares the host lock with restore; no root UI or full repository capability. */
internal class ExpenseEditorController(
    private val state: SliceState<ExpenseEditorState>,
    private val writes: ExpenseWritePort,
    private val context: ExpenseEditContextReader,
    private val categories: ReadState<List<ExpenseCategory>>,
    private val displayedEpoch: ReadState<Long>,
    private val history: ExpenseHistoryMembership,
    private val navigation: EditorNavigation,
    private val commits: EditorCommitPort,
    private val effects: ExpenseMutationEffects,
    private val runner: ForegroundMutationRunner,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val admissionLock: Any,
) {
    private val entrySlot = EditorSlot({ it.entrySaving }) { s, pending -> s.copy(entrySaving = pending) }
    private val editSlot = EditorSlot({ it.expenseEditSaving }) { s, pending -> s.copy(expenseEditSaving = pending) }

    fun submitNewExpense(input: NewExpenseInput) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        runner.launch(ticket, entrySlot,
            validationMessage = { UiMessage("请检查金额、名称和分类").takeUnless { input.isValid(categories.value) } },
            mutation = {
                if (input.categoryExplicit) writes.addExpense(input.amountCents, input.categoryKey, input.name.trim(), input.note.trim())
                else writes.addExpense(input.copy(name = input.name.trim(), note = input.note.trim()))
            },
            translate = { MutationResult(it, if (it) null else UiMessage("请检查金额、名称和分类")) },
            applyCommitted = { navigation.closeEntryIfCurrent(); Unit },
            refreshScope = RefreshScope.HOME, refreshDomains = setOf(DataDomain.LEDGER),
            failureMessage = UiMessage("保存失败，请重试"))
    }

    fun updateExpense(id: Long, input: ExpenseEditInput) = synchronized(admissionLock) {
        val captured = context.read(id)
        val ticket = coordinator.mutationTicket(captured.epoch) ?: return@synchronized
        val refreshScope = if (input.date != null || input.time != null) RefreshScope.ALL else refreshScope(id)
        runner.launch(ticket, editSlot,
            validationMessage = { UiMessage("名称不能为空，备注最多 100 个字").takeUnless { input.isValid(categories.value) } },
            mutation = {
                if (!input.categoryExplicit && input.isSpecial == null) writes.updateExpense(id, input.name.trim(), input.note.trim(), input.categoryKey, input.date, input.time)
                else writes.updateExpense(id, input.copy(name = input.name.trim(), note = input.note.trim()))
            },
            translate = { MutationResult(it, if (it) null else UiMessage("名称不能为空，备注最多 100 个字")) },
            applyCommitted = {
                val original = captured.record
                val correction = if (original != null && !original.isSpecial && input.isSpecial != true &&
                    (input.categoryExplicit || original.category.key != input.categoryKey))
                    CategoryCorrection(input.categoryKey, original.merchant, input.name.trim(), ticket.epoch) else null
                commits.applySavedEdit(SavedEditTarget(id, captured.fromSearch), correction)
            },
            refreshScope = refreshScope, refreshDomains = setOf(DataDomain.LEDGER), failureMessage = UiMessage("保存失败，请重试"))
    }

    fun deleteExpense(id: Long) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket(displayedEpoch.value) ?: return@synchronized
        runner.launch(ticket, editSlot, validationMessage = { null }, mutation = { writes.deleteExpense(id) },
            translate = { MutationResult(true) }, applyCommitted = { effects.removeDisplayed(id) },
            refreshScope = refreshScope(id), refreshDomains = setOf(DataDomain.LEDGER), failureMessage = UiMessage("删除失败，请重试"))
    }

    fun dismissCategoryCorrection() = synchronized(admissionLock) { state.update { it.copy(categoryCorrection = null) } }

    /** Synchronous handle invalidation only. Composition publishes the whole restore reset afterward. */
    fun invalidateForRestore() = synchronized(admissionLock) { entrySlot.invalidate(); editSlot.invalidate() }

    private fun refreshScope(id: Long) = if (history.contains(id)) RefreshScope.ALL else RefreshScope.HOME

    private inner class EditorSlot(private val pending: (ExpenseEditorState) -> Boolean,
        private val withPending: (ExpenseEditorState, Boolean) -> ExpenseEditorState) : MutationSlot {
        private var generation = 0L
        override fun admit(): Long? = synchronized(admissionLock) {
            if (pending(state.value)) return@synchronized null
            val admitted = ++generation
            state.update { withPending(it, true) }
            admitted
        }
        override fun isCurrent(generation: Long) = synchronized(admissionLock) { this.generation == generation }
        override fun finish(generation: Long) = synchronized(admissionLock) {
            if (this.generation == generation) state.update { withPending(it, false) }
        }
        fun invalidate() { generation++ }
    }
}

private fun NewExpenseInput.isValid(categories: List<ExpenseCategory>) =
    amountCents in 1..MoneyLimits.MAX_CENTS && name.trim().isNotEmpty() && name.trim().length <= 40 &&
        note.trim().length <= 100 && categories.any { it.key == categoryKey }

private fun ExpenseEditInput.isValid(categories: List<ExpenseCategory>) =
    name.trim().isNotEmpty() && name.trim().length <= 40 && note.trim().length <= 100 && categories.any { it.key == categoryKey }
