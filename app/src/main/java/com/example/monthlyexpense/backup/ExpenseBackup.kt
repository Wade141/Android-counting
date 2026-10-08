package com.example.monthlyexpense.backup

import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.MoneyLimits
import java.time.YearMonth
import com.example.monthlyexpense.classification.*

data class ExpenseBackupDocument(
    val exportedAt: Long,
    val database: ExpenseBackupDatabase,
    val settings: ExpenseBackupSettings
)

data class ExpenseBackupDatabase(
    val categories: List<BackupCategory>,
    val expenses: List<BackupExpense>,
    val monthlyBudgets: List<BackupMonthlyBudget>,
    val categoryRules: List<CategoryRuleSet> = emptyList()
)

data class BackupCategory(
    val key: String,
    val name: String,
    val colorArgb: Long,
    val builtIn: Boolean,
    val sortOrder: Int
)

data class BackupExpense(
    val id: Long,
    val amountCents: Long,
    val categoryKey: String,
    val name: String,
    val note: String,
    val spentAt: Long,
    val monthKey: String,
    val archived: Boolean,
    val source: String,
    val merchant: String?,
    val sourceKey: String?,
    val classificationOrigin: ClassificationOrigin = ClassificationOrigin.LEGACY,
    val classificationHits: List<CategoryRuleHit> = emptyList(),
    val isSpecial: Boolean = false
)

data class BackupMonthlyBudget(val monthKey: String, val amountCents: Long)

data class ExpenseBackupSettings(
    val dailyBudgetCents: Long,
    val autoBookkeepingEnabled: Boolean,
    val weChatEnabled: Boolean,
    val alipayEnabled: Boolean
)

internal object ExpenseBackupValidator {
    private val builtInKeys = setOf(
        BuiltInCategoryKeys.FOOD,
        BuiltInCategoryKeys.ENTERTAINMENT,
        BuiltInCategoryKeys.TRAVEL,
        BuiltInCategoryKeys.SHOPPING,
        BuiltInCategoryKeys.BILLS,
        BuiltInCategoryKeys.OTHER
    )

    fun isValid(document: ExpenseBackupDocument): Boolean =
        document.exportedAt >= 0 &&
            document.settings.dailyBudgetCents in 0..MoneyLimits.MAX_CENTS &&
            isValid(document.database)

    fun isValid(database: ExpenseBackupDatabase): Boolean {
        val categories = database.categories
        if (categories.isEmpty() || categories.any {
                it.key.isBlank() || it.name.isBlank() || it.name.length > 20 || it.sortOrder < 0
            }
        ) return false
        if (categories.map { it.key }.toSet().size != categories.size) return false
        if (categories.map { it.name.lowercase() }.toSet().size != categories.size) return false
        if (categories.filter { it.builtIn }.map { it.key }.toSet() != builtInKeys) return false
        if (categories.any { it.key in builtInKeys && !it.builtIn }) return false

        val categoryKeys = categories.mapTo(mutableSetOf()) { it.key }
        if (validateRuleSets(database.categoryRules, categoryKeys) != null) return false
        val expenseIds = mutableSetOf<Long>()
        val sourceKeys = mutableSetOf<String>()
        if (database.expenses.any { expense ->
                !validEvidence(expense) || expense.id <= 0 || !expenseIds.add(expense.id) ||
                    expense.amountCents !in 1..MoneyLimits.MAX_CENTS ||
                    expense.categoryKey !in categoryKeys ||
                    expense.name.isBlank() || expense.name.length > 40 || expense.note.length > 100 ||
                    !validMonth(expense.monthKey) ||
                    runCatching { ExpenseSource.valueOf(expense.source) }.isFailure ||
                    expense.sourceKey?.let { it.isBlank() || !sourceKeys.add(it) } == true
            }
        ) return false

        val budgetMonths = mutableSetOf<String>()
        return database.monthlyBudgets.none {
            !validMonth(it.monthKey) || !budgetMonths.add(it.monthKey) ||
                it.amountCents !in 0..MoneyLimits.MAX_CENTS
        }
    }

    private fun validEvidence(expense: BackupExpense): Boolean = runCatching {
        val hits = expense.classificationHits
        ClassificationJson.decodeHits(ClassificationJson.encodeHits(hits))
        if (expense.classificationOrigin == ClassificationOrigin.KEYWORD) hits.isNotEmpty() && hits.all { it.categoryKey == expense.categoryKey }
        else hits.isEmpty()
    }.getOrDefault(false)

    private fun validMonth(value: String): Boolean = runCatching {
        YearMonth.parse(value).toString() == value
    }.getOrDefault(false)
}

class InvalidExpenseBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
