package com.example.monthlyexpense.classification

import com.example.monthlyexpense.BuiltInCategoryKeys

object ExpenseCategoryResolver {
    fun resolve(input: ClassificationInput, rules: List<CategoryRuleSet>, explicitCategoryKey: String? = null,
                historyCategoryKey: String? = null): ResolvedCategory {
        if (explicitCategoryKey != null) return ResolvedCategory(explicitCategoryKey, ClassificationOrigin.MANUAL)
        return when (val match = CategoryRuleMatcher.match(input, rules)) {
            is CategoryMatch.Matched -> ResolvedCategory(match.categoryKey, ClassificationOrigin.KEYWORD, match.hits.take(20))
            is CategoryMatch.Conflict -> ResolvedCategory(BuiltInCategoryKeys.OTHER, ClassificationOrigin.CONFLICT)
            CategoryMatch.NoMatch -> if (historyCategoryKey != null) ResolvedCategory(historyCategoryKey, ClassificationOrigin.HISTORY)
                else ResolvedCategory(BuiltInCategoryKeys.OTHER, ClassificationOrigin.DEFAULT)
        }
    }
}
