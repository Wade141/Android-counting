package com.example.monthlyexpense.data

import com.example.monthlyexpense.search.*

import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ExpenseRepositoryContract {
    suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int = 50): ExpenseSearchPage
    suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot
    suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot = loadHomeSnapshot(now, zoneId)
    suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: ZoneId, previous: ExpenseHomeSnapshot,
        domains: Set<DataDomain>): ExpenseHomeSnapshot = loadHomeSnapshot(now, zoneId)

    suspend fun loadHistoryPage(
        cursor: HistoryCursor?,
        limit: Int = HISTORY_PAGE_SIZE,
        month: YearMonth? = null
    ): HistoryPage

    suspend fun addExpense(amountCents: Long, categoryKey: String, name: String, note: String): Boolean

    suspend fun updateExpense(id: Long, name: String, note: String, categoryKey: String, date: LocalDate? = null, time: java.time.LocalTime? = null): Boolean

    suspend fun addExpense(input: com.example.monthlyexpense.NewExpenseInput): Boolean = addExpense(input.amountCents, input.categoryKey, input.name, input.note)
    suspend fun updateExpense(id: Long, input: com.example.monthlyexpense.ExpenseEditInput): Boolean = updateExpense(id, input.name, input.note, input.categoryKey, input.date, input.time)

    suspend fun deleteExpense(id: Long)

    suspend fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult

    suspend fun updateCategory(
        categoryKey: String,
        name: String?,
        colorArgb: Long
    ): CategoryMutationResult

    suspend fun deleteCustomCategory(categoryKey: String): CategoryDeleteResult

    suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean

    suspend fun setDailyBudget(amountCents: Long)

    suspend fun setAutoBookkeepingEnabled(enabled: Boolean)

    suspend fun setWeChatEnabled(enabled: Boolean)

    suspend fun setAlipayEnabled(enabled: Boolean)
}

class ExpenseRepository(
    private val database: ExpenseDatabaseHelper,
    private val budgetSettings: BudgetSettings,
    private val autoSettings: AutoBookkeepingSettings,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initializeSourcePolicy: suspend () -> Unit = {},
    private val onChanged: (Set<DataDomain>) -> Unit = {}
) : ExpenseRepositoryContract {
    override suspend fun loadSearchPage(query: ExpenseSearchQuery, cursor: ExpenseSearchCursor?, limit: Int): ExpenseSearchPage =
        withContext(ioDispatcher) { database.expenseDao.searchPage(query, cursor, limit) }

    override suspend fun loadReadOnlyHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot = withContext(ioDispatcher) {
        val homeData = database.loadReadOnlyHomeData(now, zoneId)
        ExpenseHomeSnapshot(homeData.expenses, homeData.categories, homeData.monthlyBudgetCents,
            budgetSettings.dailyBudgetCents, homeData.todayTotalCents,
            autoSettings.isEnabled, autoSettings.isWeChatEnabled, autoSettings.isAlipayEnabled)
    }

    override suspend fun refreshHomeSnapshot(now: LocalDate, zoneId: ZoneId, previous: ExpenseHomeSnapshot,
        domains: Set<DataDomain>): ExpenseHomeSnapshot = withContext(ioDispatcher) {
        if (DataDomain.LEDGER !in domains && DataDomain.SETTINGS !in domains) return@withContext previous
        if (DataDomain.LEDGER in domains && DataDomain.SETTINGS in domains) return@withContext loadHomeSnapshot(now, zoneId)
        if (DataDomain.LEDGER in domains) {
            val (expenses, todayTotal) = database.loadLedgerData(now, zoneId)
            previous.copy(expenses = expenses, todayTotalCents = todayTotal)
        } else {
            val (categories, monthlyBudget) = database.loadSettingsData(now)
            previous.copy(categories = categories, monthlyBudgetCents = monthlyBudget,
                dailyBudgetCents = budgetSettings.dailyBudgetCents, autoBookkeepingEnabled = autoSettings.isEnabled,
                weChatEnabled = autoSettings.isWeChatEnabled, alipayEnabled = autoSettings.isAlipayEnabled)
        }
    }
    override suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot =
        withContext(ioDispatcher) {
            val homeData = database.loadHomeData(now, zoneId)
            ExpenseHomeSnapshot(
                expenses = homeData.expenses,
                categories = homeData.categories,
                monthlyBudgetCents = homeData.monthlyBudgetCents,
                dailyBudgetCents = budgetSettings.dailyBudgetCents,
                todayTotalCents = homeData.todayTotalCents,
                autoBookkeepingEnabled = autoSettings.isEnabled,
                weChatEnabled = autoSettings.isWeChatEnabled,
                alipayEnabled = autoSettings.isAlipayEnabled
            )
        }

    override suspend fun loadHistoryPage(
        cursor: HistoryCursor?,
        limit: Int,
        month: YearMonth?
    ): HistoryPage = withContext(ioDispatcher) {
        database.expenseDao.historyPage(cursor, limit, month)
    }

    override suspend fun addExpense(
        amountCents: Long,
        categoryKey: String,
        name: String,
        note: String
    ): Boolean = withContext(ioDispatcher) {
        database.expenseDao.addExpense(amountCents, categoryKey, name, note).also { if (it) onChanged(setOf(DataDomain.LEDGER)) }
    }

    override suspend fun updateExpense(
        id: Long,
        name: String,
        note: String,
        categoryKey: String,
        date: LocalDate?,
        time: java.time.LocalTime?
    ): Boolean = withContext(ioDispatcher) {
        database.expenseDao.updateExpense(id, name, note, categoryKey, date, time).also { if (it) onChanged(setOf(DataDomain.LEDGER)) }
    }

    override suspend fun addExpense(input: com.example.monthlyexpense.NewExpenseInput): Boolean = withContext(ioDispatcher) {
        database.expenseDao.addExpense(input.amountCents, input.categoryKey, input.name, input.note, categoryExplicit = input.categoryExplicit).also { if (it) onChanged(setOf(DataDomain.LEDGER)) }
    }
    override suspend fun updateExpense(id: Long, input: com.example.monthlyexpense.ExpenseEditInput): Boolean = withContext(ioDispatcher) {
        database.expenseDao.updateExpense(id, input.name, input.note, input.categoryKey, input.date, input.time, input.categoryExplicit, input.isSpecial).also { if (it) onChanged(setOf(DataDomain.LEDGER)) }
    }

    override suspend fun deleteExpense(id: Long) = withContext(ioDispatcher) {
        database.expenseDao.delete(id)
        onChanged(setOf(DataDomain.LEDGER))
    }

    override suspend fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult =
        withContext(ioDispatcher) { database.categoryDao.addCustomCategory(name, colorArgb).also {
            if (it is CategoryMutationResult.Success) onChanged(setOf(DataDomain.SETTINGS))
        } }

    override suspend fun updateCategory(
        categoryKey: String,
        name: String?,
        colorArgb: Long
    ): CategoryMutationResult = withContext(ioDispatcher) {
        database.categoryDao.updateCategoryDefinition(categoryKey, name, colorArgb).also {
            if (it is CategoryMutationResult.Success) onChanged(setOf(DataDomain.SETTINGS, DataDomain.LEDGER))
        }
    }

    override suspend fun deleteCustomCategory(categoryKey: String): CategoryDeleteResult =
        withContext(ioDispatcher) { database.categoryDao.deleteCustomCategory(categoryKey).also {
            if (it == CategoryDeleteResult.Deleted) onChanged(setOf(DataDomain.SETTINGS))
        } }

    override suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean =
        withContext(ioDispatcher) { database.budgetDao.setMonthlyBudget(month, amountCents).also {
            if (it) onChanged(setOf(DataDomain.SETTINGS))
        } }

    override suspend fun setDailyBudget(amountCents: Long) = withContext(ioDispatcher) {
        budgetSettings.dailyBudgetCents = amountCents
        onChanged(setOf(DataDomain.SETTINGS))
    }

    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        initializeSourcePolicy()
        autoSettings.isEnabled = enabled
        onChanged(setOf(DataDomain.SETTINGS))
    }

    override suspend fun setWeChatEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        initializeSourcePolicy()
        autoSettings.isWeChatEnabled = enabled
        onChanged(setOf(DataDomain.SETTINGS))
    }

    override suspend fun setAlipayEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        initializeSourcePolicy()
        autoSettings.isAlipayEnabled = enabled
        onChanged(setOf(DataDomain.SETTINGS))
    }
}
