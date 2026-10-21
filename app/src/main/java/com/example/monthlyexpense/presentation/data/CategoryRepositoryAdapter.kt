package com.example.monthlyexpense.presentation.data

import com.example.monthlyexpense.categories.api.CategoryWritePort
import com.example.monthlyexpense.data.ExpenseRepositoryContract

internal class CategoryRepositoryAdapter(private val repository: ExpenseRepositoryContract) : CategoryWritePort {
    override suspend fun addCustomCategory(name: String, colorArgb: Long) = repository.addCustomCategory(name, colorArgb)
    override suspend fun updateCategory(categoryKey: String, name: String?, colorArgb: Long) = repository.updateCategory(categoryKey, name, colorArgb)
    override suspend fun deleteCustomCategory(categoryKey: String) = repository.deleteCustomCategory(categoryKey)
}
