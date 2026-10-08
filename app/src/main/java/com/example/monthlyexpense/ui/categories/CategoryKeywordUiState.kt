package com.example.monthlyexpense.ui.categories

import com.example.monthlyexpense.classification.*

data class CategoryKeywordUiState(
    val draft: CategoryRuleSet,
    val epoch: Long,
    val input: String = "",
    val inputMode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
    val editingId: String? = null,
    val testMerchant: String = "",
    val testName: String = "",
    val preview: CategoryMatch? = null,
    val error: String? = null,
    val conflictingCategoryKey: String? = null,
    val saving: Boolean = false
)
data class CategoryKeywordsState(val rules: List<CategoryRuleSet> = emptyList(),
    val editor: CategoryKeywordUiState? = null, val error: String? = null, val loading: Boolean = false)
