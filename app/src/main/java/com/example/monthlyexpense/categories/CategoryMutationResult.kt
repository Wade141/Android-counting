package com.example.monthlyexpense.categories

import com.example.monthlyexpense.ExpenseCategory

sealed interface CategoryMutationResult {
    data class Success(val category: ExpenseCategory) : CategoryMutationResult
    data object DuplicateName : CategoryMutationResult
    data object InvalidName : CategoryMutationResult
    data object BuiltInNameLocked : CategoryMutationResult
    data object NotFound : CategoryMutationResult
}

sealed interface CategoryDeleteResult {
    data object Deleted : CategoryDeleteResult
    data class InUse(val expenseCount: Int) : CategoryDeleteResult
    data object BuiltInLocked : CategoryDeleteResult
    data object NotFound : CategoryDeleteResult
}
