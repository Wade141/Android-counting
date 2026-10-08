package com.example.monthlyexpense.classification

import androidx.lifecycle.SavedStateHandle
import com.example.monthlyexpense.*
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CategoryCorrectionTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun shortcutAppearsOnlyAfterSuccessfulExplicitCorrection() = runTest {
        val food = ExpenseCategory("FOOD", "饮食", 0, true, 0)
        val other = ExpenseCategory("OTHER", "其他", 0, true, 1)
        val record = ExpenseRecord(1, 100, other, "咖啡", "", System.currentTimeMillis(), ExpenseSource.WECHAT_AUTO, "咖啡店")
        val repository = FakeExpenseRepository().apply { homeSnapshot = homeSnapshot.copy(categories = listOf(food, other), expenses = listOf(record)) }
        val vm = ExpenseViewModel(repository, FakeExpenseBackupManager(), SavedStateHandle(), UnconfinedTestDispatcher(testScheduler))
        vm.refresh(NotificationListenerConnectionState.CONNECTED); advanceUntilIdle()
        vm.updateExpense(1, ExpenseEditInput(record.name, "只改备注", "OTHER")); advanceUntilIdle()
        assertNull(vm.uiState.value.categoryCorrection)
        repository.updateExpenseResult = false
        vm.updateExpense(1, ExpenseEditInput(record.name, "", "FOOD", categoryExplicit = true)); advanceUntilIdle()
        assertNull(vm.uiState.value.categoryCorrection)
        repository.updateExpenseResult = true
        vm.updateExpense(1, ExpenseEditInput(record.name, "", "FOOD", categoryExplicit = true)); advanceUntilIdle()
        assertEquals("FOOD", vm.uiState.value.categoryCorrection!!.categoryKey)
        assertEquals("咖啡店", vm.uiState.value.categoryCorrection!!.merchant)
        vm.dismissCategoryCorrection()
        assertNull(vm.uiState.value.categoryCorrection)
        assertEquals(3, repository.updateExpenseCalls)
    }
}
