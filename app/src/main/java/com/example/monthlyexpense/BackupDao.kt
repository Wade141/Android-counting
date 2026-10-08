package com.example.monthlyexpense

import android.content.ContentValues
import com.example.monthlyexpense.classification.*
import com.example.monthlyexpense.backup.BackupCategory
import com.example.monthlyexpense.backup.BackupExpense
import com.example.monthlyexpense.backup.BackupMonthlyBudget
import com.example.monthlyexpense.backup.ExpenseBackupDatabase
import com.example.monthlyexpense.backup.ExpenseBackupValidator

class BackupDao internal constructor(
    private val helper: ExpenseDatabaseHelper,
    private val categoryDao: CategoryDao
) {
    fun exportBackupDatabase(): ExpenseBackupDatabase {
        val database = helper.readableDatabase
        database.beginTransaction()
        return try {
            val backupCategories = categoryDao.categories(database).map {
                BackupCategory(it.key, it.name, it.colorArgb, it.builtIn, it.sortOrder)
            }
            val categoryNames = backupCategories.associate { it.key to it.name }
            val backup = ExpenseBackupDatabase(
                categories = backupCategories,
                categoryRules = helper.categoryRuleDao.loadRuleSets(database),
                expenses = database.rawQuery(
                    """SELECT id, amount_cents, category, name, note, spent_at, month_key, archived,
                        source, merchant, source_key, classification_origin, classification_match_json, is_special FROM expenses ORDER BY id ASC""".trimIndent(),
                    null
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                BackupExpense(
                                    id = cursor.getLong(0),
                                    amountCents = cursor.getLong(1),
                                    categoryKey = cursor.getString(2),
                                    name = cursor.getString(3).takeIf { it.isNotBlank() }
                                        ?: categoryNames[cursor.getString(2)].orEmpty(),
                                    note = cursor.getString(4),
                                    spentAt = cursor.getLong(5),
                                    monthKey = cursor.getString(6),
                                    archived = cursor.getInt(7) != 0,
                                    source = cursor.getString(8),
                                    merchant = if (cursor.isNull(9)) null else cursor.getString(9),
                                    sourceKey = if (cursor.isNull(10)) null else cursor.getString(10),
                                    classificationOrigin = ClassificationOrigin.valueOf(cursor.getString(11)),
                                    classificationHits = ClassificationJson.decodeHits(if (cursor.isNull(12)) null else cursor.getString(12)),
                                    isSpecial = cursor.getInt(13) != 0
                                )
                            )
                        }
                    }
                },
                monthlyBudgets = database.rawQuery(
                    "SELECT month_key, amount_cents FROM monthly_budgets ORDER BY month_key ASC",
                    null
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(BackupMonthlyBudget(cursor.getString(0), cursor.getLong(1)))
                        }
                    }
                }
            )
            database.setTransactionSuccessful()
            backup
        } finally {
            database.endTransaction()
        }
    }

    val notificationProtocol get() = com.example.monthlyexpense.notification.repository.LedgerNotificationProtocol(helper)

    fun restoreBackupDatabase(backup: ExpenseBackupDatabase, restoreId: String? = null,
        generation: String? = null, targetSettingsJson: String? = null): Boolean {
        if (!ExpenseBackupValidator.isValid(backup)) return false
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            database.delete("expenses", null, null)
            // Generation and restore intent commit with the replacement, never in a second database.
            notificationProtocol.markRestoreCommitted(database, restoreId ?: java.util.UUID.randomUUID().toString(),
                generation ?: java.util.UUID.randomUUID().toString(), targetSettingsJson ?: "{}")
            database.execSQL("UPDATE notification_events SET expense_id = NULL")
            database.delete("monthly_budgets", null, null)
            database.delete("category_keyword_rules", null, null)
            database.delete("category_rule_sets", null, null)
            database.delete("categories", null, null)
            backup.categories.forEach { category ->
                database.insertOrThrow("categories", null, ContentValues().apply {
                    put("category_key", category.key)
                    put("name", category.name)
                    put("color_argb", category.colorArgb)
                    put("built_in", if (category.builtIn) 1 else 0)
                    put("sort_order", category.sortOrder)
                })
            }
            backup.categoryRules.forEach { helper.categoryRuleDao.replaceRuleSet(database, it) }
            backup.expenses.forEach { expense ->
                database.insertOrThrow("expenses", null, ContentValues().apply {
                    put("id", expense.id)
                    put("amount_cents", expense.amountCents)
                    put("category", expense.categoryKey)
                    put("name", expense.name)
                    put("note", expense.note)
                    put("spent_at", expense.spentAt)
                    put("month_key", expense.monthKey)
                    put("archived", if (expense.archived) 1 else 0)
                    put("source", expense.source)
                    put("classification_origin", expense.classificationOrigin.name)
                    put("is_special", if (expense.isSpecial) 1 else 0)
                    put("classification_match_json", ClassificationJson.encodeHits(expense.classificationHits))
                    if (expense.merchant == null) putNull("merchant") else put("merchant", expense.merchant)
                    if (expense.sourceKey == null) putNull("source_key") else put("source_key", expense.sourceKey)
                })
            }
            backup.monthlyBudgets.forEach { budget ->
                database.insertOrThrow("monthly_budgets", null, ContentValues().apply {
                    put("month_key", budget.monthKey)
                    put("amount_cents", budget.amountCents)
                })
            }
            if (restoreId == null) notificationProtocol.completeRestore(
                requireNotNull(notificationProtocol.pendingRestore()).restoreId)
            database.setTransactionSuccessful()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            database.endTransaction()
        }
    }
}
