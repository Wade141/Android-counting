package com.example.monthlyexpense.classification

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.example.monthlyexpense.ExpenseDatabaseHelper

sealed interface RuleSaveResult {
    data class Success(val ruleSet: CategoryRuleSet) : RuleSaveResult
    data class Invalid(val message: String, val conflictingCategoryKey: String? = null) : RuleSaveResult
    data object Stale : RuleSaveResult
}

fun validateRuleSets(sets: List<CategoryRuleSet>, categoryKeys: Set<String>): RuleSaveResult.Invalid? {
    if (sets.map { it.categoryKey }.distinct().size != sets.size) return RuleSaveResult.Invalid("分类规则重复")
    if (sets.sumOf { it.keywords.size } > 500) return RuleSaveResult.Invalid("全部分类最多 500 个关键词")
    val ids = mutableSetOf<String>()
    for (set in sets) {
        // Revision zero belongs only to an unsaved editor draft; every persisted snapshot starts at one.
        if (set.categoryKey !in categoryKeys || set.revision <= 0) return RuleSaveResult.Invalid("分类不存在或规则版本无效")
        if (set.keywords.size > 50) return RuleSaveResult.Invalid("每个分类最多 50 个关键词")
        if (set.enabled && set.keywords.isEmpty()) return RuleSaveResult.Invalid("请先添加关键词再开启自动归类")
        val seen = mutableSetOf<Pair<String, KeywordMatchMode>>()
        for (word in set.keywords) {
            keywordValidationError(word.keyword)?.let { return RuleSaveResult.Invalid(it) }
            if (word.id.isBlank() || word.id.length > 100 || !ids.add(word.id)) return RuleSaveResult.Invalid("关键词标识重复或无效")
            if (word.normalizedKeyword != normalizeKeyword(word.keyword)) return RuleSaveResult.Invalid("关键词格式无效")
            if (!seen.add(word.normalizedKeyword to word.mode)) return RuleSaveResult.Invalid("同一分类中关键词和匹配方式不能重复")
        }
    }
    for (i in sets.indices) for (j in i + 1 until sets.size) {
        val a = sets[i]; val b = sets[j]
        if (!a.enabled || !b.enabled) continue
        if (a.matchField != b.matchField && a.matchField != CategoryMatchField.MERCHANT_OR_NAME && b.matchField != CategoryMatchField.MERCHANT_OR_NAME) continue
        if (a.keywords.any { x -> b.keywords.any { it.normalizedKeyword == x.normalizedKeyword && it.mode == x.mode } })
            return RuleSaveResult.Invalid("相同关键词已用于其他分类，请修改关键词、范围或停用其中一组", b.categoryKey)
    }
    return null
}

class CategoryRuleDao(private val helper: ExpenseDatabaseHelper) {
    fun loadRuleSets(database: SQLiteDatabase = helper.readableDatabase): List<CategoryRuleSet> {
        database.beginTransactionNonExclusive()
        return try { readRuleSets(database).also { database.setTransactionSuccessful() } }
        finally { database.endTransaction() }
    }

    private fun readRuleSets(database: SQLiteDatabase): List<CategoryRuleSet> {
        val words = database.rawQuery("SELECT id, category_key, keyword, normalized_keyword, match_mode FROM category_keyword_rules ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to CategoryKeywordRule(c.getString(0), c.getString(2), c.getString(3), KeywordMatchMode.valueOf(c.getString(4)))) }
        }.groupBy({ it.first }, { it.second })
        return database.rawQuery("SELECT category_key, enabled, match_field, revision FROM category_rule_sets ORDER BY category_key", null).use { c ->
            buildList { while (c.moveToNext()) add(CategoryRuleSet(c.getString(0), c.getInt(1) != 0, CategoryMatchField.valueOf(c.getString(2)), c.getLong(3), words[c.getString(0)].orEmpty())) }
        }
    }

    fun saveRuleSet(draft: CategoryRuleSet, expectedRevision: Long): RuleSaveResult {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val current = loadRuleSets(db)
            if ((current.find { it.categoryKey == draft.categoryKey }?.revision ?: 0) != expectedRevision) return RuleSaveResult.Stale
            val normalized = draft.copy(revision = expectedRevision + 1, keywords = draft.keywords.map { it.copy(normalizedKeyword = normalizeKeyword(it.keyword)) })
            // Draft first makes a conflict point to the other, saved category.
            validateRuleSets(listOf(normalized) + current.filter { it.categoryKey != draft.categoryKey }, helper.categoryDao.categories(db).map { it.key }.toSet())?.let { return it }
            replaceRuleSet(db, normalized)
            db.setTransactionSuccessful()
            RuleSaveResult.Success(normalized)
        } finally { db.endTransaction() }
    }

    internal fun deleteRulesForCategory(db: SQLiteDatabase, key: String) {
        db.delete("category_keyword_rules", "category_key = ?", arrayOf(key))
        db.delete("category_rule_sets", "category_key = ?", arrayOf(key))
    }

    internal fun replaceRuleSet(db: SQLiteDatabase, set: CategoryRuleSet) {
        deleteRulesForCategory(db, set.categoryKey)
        db.insertOrThrow("category_rule_sets", null, ContentValues().apply {
            put("category_key", set.categoryKey); put("enabled", if (set.enabled) 1 else 0)
            put("match_field", set.matchField.name); put("revision", set.revision)
        })
        set.keywords.forEach { word -> db.insertOrThrow("category_keyword_rules", null, ContentValues().apply {
            put("id", word.id); put("category_key", set.categoryKey); put("keyword", word.keyword)
            put("normalized_keyword", word.normalizedKeyword); put("match_mode", word.mode.name)
        }) }
    }
}
