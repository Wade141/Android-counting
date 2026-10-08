package com.example.monthlyexpense

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.data.ExpenseRepository
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpecialBudgetViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun confirmedMovePersistsAndRefreshesHomeEvenWithoutCategoryChange() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("expenses.db")
        val database = ExpenseDatabaseHelper(context)
        val repository = ExpenseRepository(database, BudgetSettings(context), AutoBookkeepingSettings(context),
            UnconfinedTestDispatcher(testScheduler))
        val vm = ExpenseViewModel(repository, FakeExpenseBackupManager(), SavedStateHandle(),
            UnconfinedTestDispatcher(testScheduler))
        try {
            database.expenseDao.addExpense(6_021_680, "OTHER", "学费", "原备注")
            database.expenseDao.addExpense(132_377, "FOOD", "生活费", "")
            vm.refresh(NotificationListenerConnectionState.NOT_AUTHORIZED)
            advanceUntilIdle()
            val id = vm.uiState.value.expenses.single { it.name == "学费" }.id
            vm.showOverlay(ExpenseOverlay.EditExpense(id))
            vm.updateExpense(id, ExpenseEditInput("学费", "本学年", "OTHER", categoryExplicit = true, isSpecial = true))
            advanceUntilIdle()
            assertTrue(database.expenseDao.currentMonthRecords().single { it.id == id }.isSpecial)
            assertNull(vm.uiState.value.overlay)
            assertNull(vm.uiState.value.categoryCorrection)
            assertEquals(132_377L, vm.uiState.value.todayTotalCents)
            assertEquals(132_377L, ExpenseTotals.from(vm.uiState.value.expenses).monthlyCents)
            assertEquals(6_154_057L, ExpenseTotals.from(vm.uiState.value.expenses).allCents)
            vm.showOverlay(ExpenseOverlay.EditExpense(id, returnToSpecialBudget = true))
            vm.dismissOverlay()
            assertEquals(ExpenseOverlay.SpecialBudgetDetails, vm.uiState.value.overlay)
            vm.showOverlay(ExpenseOverlay.EditExpense(id, returnToSpecialBudget = true))
            vm.updateExpense(id, ExpenseEditInput("学费", "本学年", "BILLS", categoryExplicit = true))
            advanceUntilIdle()
            assertNull(vm.uiState.value.categoryCorrection)
            assertEquals(ExpenseOverlay.SpecialBudgetDetails, vm.uiState.value.overlay)
            vm.showOverlay(ExpenseOverlay.EditExpense(id, returnToSpecialBudget = true))
            vm.updateExpense(id, ExpenseEditInput("", "无效", "OTHER", isSpecial = false))
            advanceUntilIdle()
            assertEquals(ExpenseOverlay.EditExpense(id, returnToSpecialBudget = true), vm.uiState.value.overlay)
            assertTrue(database.expenseDao.currentMonthRecords().single { it.id == id }.isSpecial)
            vm.updateExpense(id, ExpenseEditInput("学费", "本学年", "OTHER", isSpecial = false))
            advanceUntilIdle()
            assertEquals(6_154_057L, vm.uiState.value.todayTotalCents)
            assertFalse(vm.uiState.value.expenses.single { it.id == id }.isSpecial)
            assertEquals(ExpenseOverlay.SpecialBudgetDetails, vm.uiState.value.overlay)
            vm.dismissOverlay()
            assertNull(vm.uiState.value.overlay)
        } finally {
            vm.viewModelScope.cancel()
            database.close()
            context.deleteDatabase("expenses.db")
        }
    }
}
