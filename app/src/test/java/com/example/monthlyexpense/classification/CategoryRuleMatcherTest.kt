package com.example.monthlyexpense.classification

import org.junit.Assert.*
import org.junit.Test

class CategoryRuleMatcherTest {
    private fun rule(category: String, word: String, mode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
                     field: CategoryMatchField = CategoryMatchField.MERCHANT_OR_NAME) =
        CategoryRuleSet(category, true, field, 1, listOf(CategoryKeywordRule(category + word, word, normalizeKeyword(word), mode)))

    @Test fun twoContainsCategoriesReturnConflict() {
        val rules = listOf(rule("FOOD", "咖啡"), rule("SHOPPING", "咖啡机"))
        val result = CategoryRuleMatcher.match(ClassificationInput("咖啡机店", "消费"), rules)
        assertTrue(result is CategoryMatch.Conflict)
        assertEquals(result, CategoryRuleMatcher.match(ClassificationInput("咖啡机店", "消费"), rules.reversed()))
    }
    @Test fun exactBeatsContains() {
        val result = CategoryRuleMatcher.match(ClassificationInput("咖啡机店", "消费"), listOf(rule("FOOD", "咖啡"), rule("SHOPPING", "咖啡机店", KeywordMatchMode.EXACT)))
        assertEquals("SHOPPING", (result as CategoryMatch.Matched).categoryKey)
    }
    @Test fun literalPercentDoesNotMatchArbitraryText() {
        for (word in listOf("100%", "a_b", ".*", "\\")) {
            assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput("100元 ab abc", ""), listOf(rule("FOOD", word))))
        }
    }
    @Test fun normalizesWidthCaseAndWhitespaceWithoutRemovingPunctuation() {
        assertEquals("abc 店", normalizeKeyword("  ＡＢＣ　 店  "))
        assertTrue(CategoryRuleMatcher.match(ClassificationInput("ＡＢＣ   店", ""), listOf(rule("FOOD", "abc 店"))) is CategoryMatch.Matched)
        assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput("ab-c", ""), listOf(rule("FOOD", "abc"))))
    }
    @Test fun fieldsAreSeparateAndMissingMerchantDoesNotFallBack() {
        assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput("咖啡", "机"), listOf(rule("SHOPPING", "咖啡机"))))
        assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput(null, "咖啡"), listOf(rule("FOOD", "咖啡", field = CategoryMatchField.MERCHANT))))
    }
    @Test fun ignoresDisabledAndPlaceholderNames() {
        assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput(null, "微信自动记账"), listOf(rule("FOOD", "微信"))))
        assertEquals(CategoryMatch.NoMatch, CategoryRuleMatcher.match(ClassificationInput("咖啡", ""), listOf(rule("FOOD", "咖啡").copy(enabled = false))))
    }
    @Test fun unicodeLengthAndControlsAreValidated() {
        assertNull(keywordValidationError("😀".repeat(40)))
        assertNotNull(keywordValidationError("😀".repeat(41)))
        assertNotNull(keywordValidationError("店\n名"))
        assertNotNull(keywordValidationError("　 "))
        assertNotNull(keywordValidationError("㍿".repeat(11)))
    }
    @Test fun multipleHitsInOneCategoryAreNotAConflict() {
        val first = rule("FOOD", "咖啡")
        val second = rule("FOOD", "店")
        val result = CategoryRuleMatcher.match(ClassificationInput("咖啡店", "咖啡"), listOf(first.copy(keywords = first.keywords + second.keywords)))
        assertEquals("FOOD", (result as CategoryMatch.Matched).categoryKey)
        assertEquals(3, result.hits.size)
    }
    @Test fun manualOtherWins() {
        val result = ExpenseCategoryResolver.resolve(ClassificationInput("咖啡", ""), listOf(rule("FOOD", "咖啡")), explicitCategoryKey = "OTHER")
        assertEquals("OTHER", result.categoryKey)
        assertEquals(ClassificationOrigin.MANUAL, result.origin)
    }
    @Test fun realExpenseNameIsNotTreatedAsGeneratedPlaceholder() {
        assertTrue(CategoryRuleMatcher.match(ClassificationInput(null, "消费"), listOf(rule("SHOPPING", "消费"))) is CategoryMatch.Matched)
    }
}
