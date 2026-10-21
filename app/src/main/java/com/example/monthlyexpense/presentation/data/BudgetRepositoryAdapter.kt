package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.budget.api.BudgetWritePort
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import java.time.YearMonth

internal class BudgetRepositoryAdapter(private val repository: ExpenseRepositoryContract) : BudgetWritePort {
    override suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long) = repository.setMonthlyBudget(month, amountCents)
    override suspend fun setDailyBudget(amountCents: Long) = repository.setDailyBudget(amountCents)
}
