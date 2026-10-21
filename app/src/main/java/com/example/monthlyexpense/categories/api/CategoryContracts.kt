package com.example.monthlyexpense.categories.api

import com.example.monthlyexpense.CategoryManagementUiState
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult

/** Logical ownership only; mapped to the single presentation snapshot. */
internal data class CategoryOperationState(
    val management: CategoryManagementUiState = CategoryManagementUiState(),
    val saving: Boolean = false,
    val deleting: Boolean = false,
)

/** Suspend, single delegation to the existing repository. Result cases, exceptions and cancellation
 * retain their meanings. No dispatcher, validation, cache, refresh, or ticket acquisition here. */
internal interface CategoryWritePort {
    suspend fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult
    suspend fun updateCategory(categoryKey: String, name: String?, colorArgb: Long): CategoryMutationResult
    suspend fun deleteCustomCategory(categoryKey: String): CategoryDeleteResult
}
