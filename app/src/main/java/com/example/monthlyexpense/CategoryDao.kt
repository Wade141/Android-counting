package com.example.monthlyexpense

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import java.util.UUID

class CategoryDao internal constructor(
    private val helper: ExpenseDatabaseHelper
) {
    fun categories(): List<ExpenseCategory> = categories(helper.readableDatabase)

    internal fun categories(database: SQLiteDatabase): List<ExpenseCategory> = database.query(
        "categories",
        arrayOf("category_key", "name", "color_argb", "built_in", "sort_order"),
        null,
        null,
        null,
        null,
        "sort_order ASC, category_key ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ExpenseCategory(
                        key = cursor.getString(0),
                        name = cursor.getString(1),
                        colorArgb = cursor.getLong(2),
                        builtIn = cursor.getInt(3) != 0,
                        sortOrder = cursor.getInt(4)
                    )
                )
            }
        }
    }

    fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult {
        val normalizedName = name.normalizedCategoryName()
            ?: return CategoryMutationResult.InvalidName
        val database = helper.writableDatabase
        val nextOrder = database.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM categories",
            null
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val key = "custom:${UUID.randomUUID()}"
        return try {
            database.insertOrThrow("categories", null, ContentValues().apply {
                put("category_key", key)
                put("name", normalizedName)
                put("color_argb", colorArgb)
                put("built_in", 0)
                put("sort_order", nextOrder)
            })
            CategoryMutationResult.Success(categoryByKey(key)!!)
        } catch (_: SQLiteConstraintException) {
            CategoryMutationResult.DuplicateName
        }
    }

    fun updateCategoryDefinition(
        key: String,
        name: String?,
        colorArgb: Long
    ): CategoryMutationResult {
        val existing = categoryByKey(key) ?: return CategoryMutationResult.NotFound
        val normalizedName = if (name == null) existing.name else name.normalizedCategoryName()
            ?: return CategoryMutationResult.InvalidName
        if (existing.builtIn && normalizedName != existing.name) {
            return CategoryMutationResult.BuiltInNameLocked
        }
        return try {
            helper.writableDatabase.update(
                "categories",
                ContentValues().apply {
                    put("name", normalizedName)
                    put("color_argb", colorArgb)
                },
                "category_key = ?",
                arrayOf(key)
            )
            CategoryMutationResult.Success(categoryByKey(key)!!)
        } catch (_: SQLiteConstraintException) {
            CategoryMutationResult.DuplicateName
        }
    }

    fun categoryUsageCount(key: String): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM expenses WHERE category = ?",
        arrayOf(key)
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    fun deleteCustomCategory(key: String): CategoryDeleteResult {
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            val category = categoryByKey(key, database)
                ?: return CategoryDeleteResult.NotFound
            if (category.builtIn) return CategoryDeleteResult.BuiltInLocked
            val usageCount = database.rawQuery(
                "SELECT COUNT(*) FROM expenses WHERE category = ?",
                arrayOf(key)
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            if (usageCount > 0) return CategoryDeleteResult.InUse(usageCount)
            database.delete("categories", "category_key = ?", arrayOf(key))
            helper.categoryRuleDao.deleteRulesForCategory(database, key)
            database.setTransactionSuccessful()
            CategoryDeleteResult.Deleted
        } finally {
            database.endTransaction()
        }
    }

    internal fun categoryByKey(
        key: String,
        database: SQLiteDatabase = helper.readableDatabase
    ): ExpenseCategory? = database.query(
        "categories",
        arrayOf("category_key", "name", "color_argb", "built_in", "sort_order"),
        "category_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ExpenseCategory(
            key = cursor.getString(0),
            name = cursor.getString(1),
            colorArgb = cursor.getLong(2),
            builtIn = cursor.getInt(3) != 0,
            sortOrder = cursor.getInt(4)
        )
    }

    private fun String.normalizedCategoryName(): String? = trim()
        .takeIf { it.isNotEmpty() && it.length <= 20 }
}
