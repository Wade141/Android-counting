package com.example.monthlyexpense.classification

import org.json.JSONArray
import org.json.JSONObject

/** Bounded immutable evidence, also used by the versioned backup format. */
object ClassificationJson {
    fun encodeHits(hits: List<CategoryRuleHit>): String? = if (hits.isEmpty()) null else JSONArray().apply {
        hits.forEach { put(JSONObject().put("ruleId", it.ruleId).put("categoryKey", it.categoryKey)
            .put("keyword", it.keyword).put("field", it.field.name).put("mode", it.mode.name).put("revision", it.revision)) }
    }.toString()
    fun decodeHits(json: String?): List<CategoryRuleHit> {
        if (json == null) return emptyList()
        require(json.length <= 30000)
        val array = JSONArray(json)
        require(array.length() <= 20)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            it.keysExactly("ruleId", "categoryKey", "keyword", "field", "mode", "revision")
            CategoryRuleHit(it.string("ruleId"), it.string("categoryKey"), it.string("keyword"),
                CategoryMatchField.valueOf(it.string("field")), KeywordMatchMode.valueOf(it.string("mode")), it.integer("revision"))
        } }.also { hits -> require(hits.all { it.ruleId.isNotBlank() && it.ruleId.length <= 100 && it.categoryKey.isNotBlank() &&
            keywordValidationError(it.keyword) == null && it.field != CategoryMatchField.MERCHANT_OR_NAME && it.revision > 0 }) }
    }
    fun encodeRules(sets: List<CategoryRuleSet>): JSONArray = JSONArray().apply { sets.forEach { set ->
        put(JSONObject().put("categoryKey", set.categoryKey).put("enabled", set.enabled).put("matchField", set.matchField.name)
            .put("revision", set.revision).put("keywords", JSONArray().apply { set.keywords.forEach { word ->
                put(JSONObject().put("id", word.id).put("keyword", word.keyword).put("normalizedKeyword", word.normalizedKeyword).put("mode", word.mode.name))
            } }))
    } }
    fun decodeRules(array: JSONArray): List<CategoryRuleSet> {
        return (0 until array.length()).map { index -> array.getJSONObject(index).let { set ->
            set.keysExactly("categoryKey", "enabled", "matchField", "revision", "keywords")
            require(set.get("enabled") is Boolean)
            val words = set.getJSONArray("keywords")
            require(words.length() <= 50)
            CategoryRuleSet(set.string("categoryKey"), set.getBoolean("enabled"), CategoryMatchField.valueOf(set.string("matchField")), set.integer("revision"),
                (0 until words.length()).map { n -> words.getJSONObject(n).let {
                    it.keysExactly("id", "keyword", "normalizedKeyword", "mode")
                    CategoryKeywordRule(it.string("id"), it.string("keyword"), it.string("normalizedKeyword"), KeywordMatchMode.valueOf(it.string("mode")))
                } })
        } }
    }
    private fun JSONObject.string(key: String): String = get(key).let { require(it is String); it }
    private fun JSONObject.integer(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.keysExactly(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
}
