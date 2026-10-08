package com.example.monthlyexpense.classification

enum class KeywordMatchMode { CONTAINS, EXACT }
enum class CategoryMatchField { MERCHANT_OR_NAME, MERCHANT, NAME }
enum class ClassificationOrigin { MANUAL, KEYWORD, HISTORY, DEFAULT, CONFLICT, LEGACY }
data class CategoryKeywordRule(val id: String, val keyword: String, val normalizedKeyword: String,
                               val mode: KeywordMatchMode = KeywordMatchMode.CONTAINS)
data class CategoryRuleSet(val categoryKey: String, val enabled: Boolean = false,
                           val matchField: CategoryMatchField = CategoryMatchField.MERCHANT_OR_NAME,
                           val revision: Long = 0, val keywords: List<CategoryKeywordRule> = emptyList())
data class ClassificationInput(val merchant: String?, val name: String)
data class CategoryRuleHit(val ruleId: String, val categoryKey: String, val keyword: String,
                           val field: CategoryMatchField, val mode: KeywordMatchMode, val revision: Long)
sealed interface CategoryMatch {
    data object NoMatch : CategoryMatch
    data class Matched(val categoryKey: String, val hits: List<CategoryRuleHit>) : CategoryMatch
    data class Conflict(val hits: List<CategoryRuleHit>) : CategoryMatch
}
data class ResolvedCategory(val categoryKey: String, val origin: ClassificationOrigin,
                            val hits: List<CategoryRuleHit> = emptyList())
