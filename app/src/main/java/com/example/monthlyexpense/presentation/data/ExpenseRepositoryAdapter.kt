package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.ExpenseEditInput
import com.example.monthlyexpense.NewExpenseInput
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.entry.api.ExpenseWritePort
import java.time.LocalDate
import java.time.LocalTime

internal class ExpenseRepositoryAdapter(private val repository: ExpenseRepositoryContract) : ExpenseWritePort {
    override suspend fun addExpense(amountCents: Long, categoryKey: String, name: String, note: String) =
        repository.addExpense(amountCents, categoryKey, name, note)
    override suspend fun addExpense(input: NewExpenseInput) = repository.addExpense(input)
    override suspend fun updateExpense(id: Long, name: String, note: String, categoryKey: String, date: LocalDate?, time: LocalTime?) =
        repository.updateExpense(id, name, note, categoryKey, date, time)
    override suspend fun updateExpense(id: Long, input: ExpenseEditInput) = repository.updateExpense(id, input)
    override suspend fun deleteExpense(id: Long) = repository.deleteExpense(id)
}
