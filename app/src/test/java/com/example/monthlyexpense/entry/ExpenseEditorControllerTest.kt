package com.example.monthlyexpense.entry

import com.example.monthlyexpense.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.entry.api.*
import com.example.monthlyexpense.presentation.ForegroundMutationRunner
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.*
import com.example.monthlyexpense.refresh.api.MutationRefreshPort
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseEditorControllerTest {
    private val category = ExpenseCategory("FOOD", "饮食", 0, true, 0)
    private val row = ExpenseRecord(7, 100, category, "茶", "", 1000, ExpenseSource.MANUAL, "商家")
    private val input = ExpenseEditInput(" 新名称 ", " 备注 ", "FOOD")
    private class Slice : SliceState<ExpenseEditorState> {
        override var value = ExpenseEditorState()
        override fun update(transform: (ExpenseEditorState) -> ExpenseEditorState) { value = transform(value) }
    }
    private class Writes : ExpenseWritePort {
        val calls = mutableListOf<String>()
        var newInput: NewExpenseInput? = null
        var editInput: ExpenseEditInput? = null
        var gate: CompletableDeferred<Unit>? = null
        var accepted = true
        var ledger = mutableMapOf(7L to "原账本")
        override suspend fun addExpense(amountCents: Long, categoryKey: String, name: String, note: String): Boolean {
            calls += "add-explicit"; newInput = NewExpenseInput(amountCents, categoryKey, name, note)
            gate?.await(); return accepted
        }
        override suspend fun addExpense(input: NewExpenseInput): Boolean {
            calls += "add-input"; newInput = input; gate?.await(); return accepted
        }
        override suspend fun updateExpense(id: Long, name: String, note: String, categoryKey: String, date: LocalDate?, time: LocalTime?): Boolean {
            calls += "edit-fields"; editInput = ExpenseEditInput(name, note, categoryKey, date, time)
            gate?.await(); if (accepted) ledger[id] = name; return accepted
        }
        override suspend fun updateExpense(id: Long, input: ExpenseEditInput): Boolean {
            calls += "edit-input"; editInput = input; gate?.await(); if (accepted) ledger[id] = input.name; return accepted
        }
        override suspend fun deleteExpense(id: Long) { calls += "delete"; gate?.await(); ledger.remove(id) }
    }
    private inner class Fixture(scope: CoroutineScope) {
        val state = Slice(); val writes = Writes(); val lock = Any()
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        var epoch = 0L
        var context = ExpenseEditContext(row, 0, false, false)
        var historyContains = false
        var closes = 0
        val saved = mutableListOf<Pair<SavedEditTarget, CategoryCorrection?>>()
        val removed = mutableListOf<Long>(); val refreshes = mutableListOf<RefreshScope>(); val events = mutableListOf<UiEvent>()
        var refreshGate: CompletableDeferred<Unit>? = null
        val runner = ForegroundMutationRunner(scope, coordinator, lock, object : MutationRefreshPort {
            override suspend fun afterMutation(scope: RefreshScope, domains: Set<DataDomain>): RefreshResult {
                assertEquals(setOf(DataDomain.LEDGER), domains); refreshes += scope
                refreshGate?.await(); return RefreshResult.APPLIED
            }
        }, UiEventSink { events += it; true })
        val editor = ExpenseEditorController(state, writes, object : ExpenseEditContextReader {
            override fun read(id: Long) = context
        }, object : ReadState<List<ExpenseCategory>> { override val value get() = listOf(category) },
            object : ReadState<Long> { override val value get() = epoch },
            ExpenseHistoryMembership { historyContains }, object : EditorNavigation {
                override fun closeEntryIfCurrent(): Boolean { closes++; return true }
            }, EditorCommitPort { target, correction -> saved += target to correction },
            ExpenseMutationEffects { removed += it }, runner, coordinator, lock)
        suspend fun restore() {
            val ticket = synchronized(lock) {
                val ticket = coordinator.beginRestore()!!
                editor.invalidateForRestore(); state.value = ExpenseEditorState(); ticket
            }
            coordinator.runRestore(ticket, { writes.ledger = mutableMapOf(7L to "新账本"); RestoreCommit.NotRestored }, {})
            epoch = coordinator.currentEpoch
        }
    }

    @Test fun addPreservesOverloadTrimValidationAndSuppressesPendingDuplicate() = runTest {
        val f = Fixture(this); f.writes.gate = CompletableDeferred()
        f.editor.submitNewExpense(NewExpenseInput(100, "FOOD", " 茶 ", " 备注 "))
        f.editor.submitNewExpense(NewExpenseInput(-1, "missing", "", "")); runCurrent()
        assertEquals(listOf("add-explicit"), f.writes.calls); assertEquals("茶", f.writes.newInput!!.name)
        assertEquals("备注", f.writes.newInput!!.note); assertTrue(f.state.value.entrySaving)
        f.writes.gate!!.complete(Unit); advanceUntilIdle()
        f.editor.submitNewExpense(NewExpenseInput(100, "FOOD", " 茶 ", "", false)); advanceUntilIdle()
        assertEquals(listOf("add-explicit", "add-input"), f.writes.calls); assertFalse(f.writes.newInput!!.categoryExplicit)
        assertEquals(2, f.closes); assertFalse(f.state.value.entrySaving)
        f.editor.submitNewExpense(NewExpenseInput(0, "FOOD", "茶", "")); advanceUntilIdle()
        assertEquals(2, f.writes.calls.size); assertEquals(1, f.events.size)
    }

    @Test fun editingUsesCapturedSearchRecordForCorrectionAndPreservesOverloadSelection() = runTest {
        val f = Fixture(this); f.context = ExpenseEditContext(row, 0, true, false)
        f.writes.gate = CompletableDeferred()
        f.editor.updateExpense(7, input.copy(categoryExplicit = true)); runCurrent()
        f.context = ExpenseEditContext(null, 0, false, false)
        f.writes.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("edit-input"), f.writes.calls)
        assertEquals(SavedEditTarget(7, true), f.saved.single().first)
        assertEquals(CategoryCorrection("FOOD", "商家", "新名称", 0), f.saved.single().second)
        f.editor.updateExpense(7, input); advanceUntilIdle()
        assertEquals(listOf("edit-input", "edit-fields"), f.writes.calls)
        assertEquals("新名称", f.writes.editInput!!.name); assertEquals("备注", f.writes.editInput!!.note)
        assertNull(f.saved.last().second) // absent original does not forbid saving
        f.editor.updateExpense(7, input.copy(isSpecial = true)); advanceUntilIdle()
        assertEquals("edit-input", f.writes.calls.last()); assertTrue(f.writes.editInput!!.isSpecial!!)
    }

    @Test fun editAndDeleteSharePendingAndRefreshScopeTracksHistoryOrDateChange() = runTest {
        val f = Fixture(this); f.refreshGate = CompletableDeferred()
        f.editor.updateExpense(7, input); runCurrent(); f.editor.deleteExpense(7); runCurrent()
        assertEquals(listOf("edit-fields"), f.writes.calls); assertTrue(f.state.value.expenseEditSaving)
        f.refreshGate!!.complete(Unit); advanceUntilIdle()
        f.historyContains = true; f.editor.deleteExpense(7); advanceUntilIdle()
        assertEquals(listOf(7L), f.removed); assertFalse(f.writes.ledger.containsKey(7))
        f.historyContains = false
        f.editor.updateExpense(7, input.copy(date = LocalDate.of(2026, 10, 18))); advanceUntilIdle()
        assertEquals(listOf(RefreshScope.HOME, RefreshScope.ALL, RefreshScope.ALL), f.refreshes)
    }

    @Test fun specialExpenseNeverOffersCategoryCorrectionAndDismissIsSliceOnly() = runTest {
        val f = Fixture(this); f.context = f.context.copy(record = row.copy(isSpecial = true))
        f.editor.updateExpense(7, input.copy(categoryExplicit = true)); advanceUntilIdle(); assertNull(f.saved.single().second)
        f.state.value = ExpenseEditorState(entrySaving = true, categoryCorrection = CategoryCorrection("FOOD", null, "茶", 0))
        f.editor.dismissCategoryCorrection(); assertNull(f.state.value.categoryCorrection); assertTrue(f.state.value.entrySaving)
    }

    @Test fun invalidOrRejectedEditKeepsNavigationAndDoesNotRefresh() = runTest {
        val f = Fixture(this)
        f.editor.updateExpense(7, input.copy(name = " ")); advanceUntilIdle(); assertTrue(f.writes.calls.isEmpty())
        f.writes.accepted = false; f.editor.updateExpense(7, input); advanceUntilIdle()
        assertTrue(f.saved.isEmpty()); assertTrue(f.refreshes.isEmpty()); assertEquals(2, f.events.size)
        assertFalse(f.state.value.expenseEditSaving)
    }

    @Test fun queuedEditAndDeleteCannotModifyRestoredSameIdAndStaleSearchContextCannotGetNewTicket() = runTest {
        for (delete in listOf(false, true)) {
            val f = Fixture(this); val blocker = CompletableDeferred<Unit>()
            val holding = launch { f.coordinator.runMutation(f.coordinator.mutationTicket()!!) { blocker.await() } }
            runCurrent()
            if (delete) f.editor.deleteExpense(7) else f.editor.updateExpense(7, input)
            runCurrent(); val restoring = launch { f.restore() }; runCurrent()
            blocker.complete(Unit); holding.join(); restoring.join(); advanceUntilIdle()
            assertEquals("新账本", f.writes.ledger[7]); assertTrue(f.writes.calls.isEmpty()); assertTrue(f.events.isEmpty())
            f.editor.updateExpense(7, input); advanceUntilIdle() // context still has old epoch
            assertTrue(f.writes.calls.isEmpty())
            f.context = ExpenseEditContext(row, f.epoch, true, false)
            f.editor.updateExpense(7, input); advanceUntilIdle(); assertEquals("新名称", f.writes.ledger[7])
        }
    }

    @Test fun restoreInvalidationPublishesNothingAndLateRefreshCannotClearNewEditPending() = runTest {
        val f = Fixture(this); val oldRefresh = CompletableDeferred<Unit>(); val newRefresh = CompletableDeferred<Unit>()
        f.refreshGate = oldRefresh; f.editor.updateExpense(7, input); runCurrent()
        val before = f.state.value; f.editor.invalidateForRestore(); assertEquals(before, f.state.value)
        f.restore(); f.context = f.context.copy(epoch = f.epoch); f.refreshGate = newRefresh
        f.editor.updateExpense(7, input.copy(name = "更新")); runCurrent()
        oldRefresh.complete(Unit); runCurrent(); assertTrue(f.state.value.expenseEditSaving)
        assertTrue(f.events.isEmpty()); newRefresh.complete(Unit); advanceUntilIdle(); assertFalse(f.state.value.expenseEditSaving)
    }
}
