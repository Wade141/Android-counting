package com.example.monthlyexpense.classification

import java.text.Normalizer
import java.util.Locale

fun normalizeKeyword(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("[\\p{Z}\\s]+"), " ").trim().lowercase(Locale.ROOT)

fun keywordValidationError(value: String): String? {
    if (value.codePoints().anyMatch { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        return "关键词不能包含控制字符"
    val normalized = normalizeKeyword(value)
    return if (value.codePointCount(0, value.length) !in 1..40 || normalized.codePointCount(0, normalized.length) !in 1..40)
        "关键词需要 1–40 个字符" else null
}

object CategoryRuleMatcher {
    private val placeholders = setOf("微信自动记账", "支付宝自动记账")
    fun match(input: ClassificationInput, rules: List<CategoryRuleSet>): CategoryMatch {
        val merchant = input.merchant?.let(::normalizeKeyword).orEmpty()
        val name = normalizeKeyword(input.name).takeUnless { it in placeholders }.orEmpty()
        val hits = rules.filter { it.enabled }.flatMap { set ->
            val fields = buildList {
                if (set.matchField != CategoryMatchField.NAME && merchant.isNotEmpty()) add(CategoryMatchField.MERCHANT to merchant)
                if (set.matchField != CategoryMatchField.MERCHANT && name.isNotEmpty()) add(CategoryMatchField.NAME to name)
            }
            set.keywords.flatMap { word -> fields.mapNotNull { (field, text) ->
                val key = word.normalizedKeyword
                val matches = key.isNotEmpty() && when (word.mode) {
                    KeywordMatchMode.EXACT -> text == key
                    KeywordMatchMode.CONTAINS -> text.contains(key)
                }
                if (matches) CategoryRuleHit(word.id, set.categoryKey, word.keyword, field, word.mode, set.revision) else null
            } }
        }
        val exact = hits.filter { it.mode == KeywordMatchMode.EXACT }
        val winning = (exact.ifEmpty { hits }).sortedWith(compareBy({ it.categoryKey }, { it.ruleId }, { it.field.name }))
        if (winning.isEmpty()) return CategoryMatch.NoMatch
        return if (winning.map { it.categoryKey }.distinct().size == 1)
            CategoryMatch.Matched(winning.first().categoryKey, winning) else CategoryMatch.Conflict(winning)
    }
}
