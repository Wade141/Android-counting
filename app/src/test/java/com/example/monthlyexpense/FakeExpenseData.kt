package com.example.monthlyexpense

import com.example.monthlyexpense.backup.ExpenseBackupContract
import com.example.monthlyexpense.backup.ExpenseBackupSettings
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred

class FakeExpenseRepository : ExpenseRepositoryContract {
    override suspend fun loadSearchPage(query: com.example.monthlyexpense.search.ExpenseSearchQuery,
        cursor: com.example.monthlyexpense.search.ExpenseSearchCursor?, limit: Int): com.example.monthlyexpense.search.ExpenseSearchPage =
        error("Search loader must be configured in tests that exercise search")

    var homeSnapshot = ExpenseHomeSnapshot(
        expenses = emptyList(),
        categories = emptyList(),
        monthlyBudgetCents = 0L,
        dailyBudgetCents = 0L,
        todayTotalCents = 0L,
        autoBookkeepingEnabled = false,
        weChatEnabled = true,
        alipayEnabled = true
    )
    var loadHomeSnapshotError: Throwable? = null
    val loadHomeSnapshotGates = mutableListOf<CompletableDeferred<Unit>>()
    var loadHomeSnapshotCalls = 0

    val historyPages = mutableMapOf<HistoryCursor?, HistoryPage>()
    val monthlyHistoryPages = mutableMapOf<Pair<YearMonth, HistoryCursor?>, HistoryPage>()
    var loadHistoryPageError: Throwable? = null
    val loadHistoryPageGates = mutableListOf<CompletableDeferred<Unit>>()
    var loadHistoryPageCalls = 0
    val loadHistoryPageArguments = mutableListOf<Pair<HistoryCursor?, Int>>()

    var addExpenseResult = true
    var addExpenseError: Throwable? = null
    val addExpenseGates = mutableListOf<CompletableDeferred<Unit>>()
    var addExpenseCalls = 0

    var updateExpenseResult = true
    var updateExpenseError: Throwable? = null
    val updateExpenseGates = mutableListOf<CompletableDeferred<Unit>>()
    var updateExpenseCalls = 0

    var deleteExpenseError: Throwable? = null
    val deleteExpenseGates = mutableListOf<CompletableDeferred<Unit>>()
    var deleteExpenseCalls = 0

    var addCategoryResult: CategoryMutationResult = CategoryMutationResult.InvalidName
    var addCategoryError: Throwable? = null
    val addCategoryGates = mutableListOf<CompletableDeferred<Unit>>()
    var addCategoryCalls = 0

    var updateCategoryResult: CategoryMutationResult = CategoryMutationResult.NotFound
    var updateCategoryError: Throwable? = null
    val updateCategoryGates = mutableListOf<CompletableDeferred<Unit>>()
    var updateCategoryCalls = 0

    var deleteCategoryResult: CategoryDeleteResult = CategoryDeleteResult.NotFound
    var deleteCategoryError: Throwable? = null
    val deleteCategoryGates = mutableListOf<CompletableDeferred<Unit>>()
    var deleteCategoryCalls = 0

    var monthlyBudgetResult = true
    var monthlyBudgetError: Throwable? = null
    val monthlyBudgetGates = mutableListOf<CompletableDeferred<Unit>>()
    var monthlyBudgetCalls = 0

    var dailyBudgetError: Throwable? = null
    val dailyBudgetGates = mutableListOf<CompletableDeferred<Unit>>()
    var dailyBudgetCalls = 0

    var autoWriteError: Throwable? = null
    val autoWriteGates = mutableListOf<CompletableDeferred<Unit>>()
    var autoWriteCalls = 0

    var weChatWriteError: Throwable? = null
    val weChatWriteGates = mutableListOf<CompletableDeferred<Unit>>()
    var weChatWriteCalls = 0

    var alipayWriteError: Throwable? = null
    val alipayWriteGates = mutableListOf<CompletableDeferred<Unit>>()
    var alipayWriteCalls = 0

    override suspend fun loadHomeSnapshot(now: LocalDate, zoneId: ZoneId): ExpenseHomeSnapshot {
        loadHomeSnapshotCalls++
        await(loadHomeSnapshotGates)
        throwIfPresent(loadHomeSnapshotError)
        return homeSnapshot
    }

    override suspend fun loadHistoryPage(cursor: HistoryCursor?, limit: Int, month: YearMonth?): HistoryPage {
        loadHistoryPageCalls++
        loadHistoryPageArguments += cursor to limit
        await(loadHistoryPageGates)
        throwIfPresent(loadHistoryPageError)
        return (if (month == null) null else monthlyHistoryPages[month to cursor])
            ?: historyPages[cursor] ?: HistoryPage(emptyList(), null, hasMore = false)
    }

    override suspend fun addExpense(
        amountCents: Long,
        categoryKey: String,
        name: String,
        note: String
    ): Boolean {
        addExpenseCalls++
        await(addExpenseGates)
        throwIfPresent(addExpenseError)
        return addExpenseResult
    }

    override suspend fun updateExpense(
        id: Long,
        name: String,
        note: String,
        categoryKey: String,
        date: LocalDate?,
        time: java.time.LocalTime?
    ): Boolean {
        updateExpenseCalls++
        await(updateExpenseGates)
        throwIfPresent(updateExpenseError)
        return updateExpenseResult
    }

    override suspend fun deleteExpense(id: Long) {
        deleteExpenseCalls++
        await(deleteExpenseGates)
        throwIfPresent(deleteExpenseError)
    }

    override suspend fun addCustomCategory(name: String, colorArgb: Long): CategoryMutationResult {
        addCategoryCalls++
        await(addCategoryGates)
        throwIfPresent(addCategoryError)
        return addCategoryResult
    }

    override suspend fun updateCategory(
        categoryKey: String,
        name: String?,
        colorArgb: Long
    ): CategoryMutationResult {
        updateCategoryCalls++
        await(updateCategoryGates)
        throwIfPresent(updateCategoryError)
        return updateCategoryResult
    }

    override suspend fun deleteCustomCategory(categoryKey: String): CategoryDeleteResult {
        deleteCategoryCalls++
        await(deleteCategoryGates)
        throwIfPresent(deleteCategoryError)
        return deleteCategoryResult
    }

    override suspend fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean {
        monthlyBudgetCalls++
        await(monthlyBudgetGates)
        throwIfPresent(monthlyBudgetError)
        return monthlyBudgetResult
    }

    override suspend fun setDailyBudget(amountCents: Long) {
        dailyBudgetCalls++
        await(dailyBudgetGates)
        throwIfPresent(dailyBudgetError)
    }

    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) {
        autoWriteCalls++
        await(autoWriteGates)
        throwIfPresent(autoWriteError)
    }

    override suspend fun setWeChatEnabled(enabled: Boolean) {
        weChatWriteCalls++
        await(weChatWriteGates)
        throwIfPresent(weChatWriteError)
    }

    override suspend fun setAlipayEnabled(enabled: Boolean) {
        alipayWriteCalls++
        await(alipayWriteGates)
        throwIfPresent(alipayWriteError)
    }

    private suspend fun await(gates: MutableList<CompletableDeferred<Unit>>) {
        if (gates.isNotEmpty()) gates.removeAt(0).await()
    }

    private fun throwIfPresent(error: Throwable?) {
        if (error != null) throw error
    }
}

class FakeExpenseBackupManager : ExpenseBackupContract {
    var jsonExport = "{}"
    var jsonExportError: Throwable? = null
    var jsonExportCalls = 0

    var csvExport = ""
    var csvExportError: Throwable? = null
    var csvExportCalls = 0

    var validateJsonResult = true
    var validateJsonError: Throwable? = null
    var validateJsonCalls = 0

    var restoreResult: ExpenseBackupSettings? = null
    var restoreError: Throwable? = null
    var restoreCalls = 0

    override fun exportJson(exportedAt: Long): String {
        jsonExportCalls++
        throwIfPresent(jsonExportError)
        return jsonExport
    }

    override fun exportCsv(zoneId: ZoneId): String {
        csvExportCalls++
        throwIfPresent(csvExportError)
        return csvExport
    }

    override fun validateJson(json: String): Boolean {
        validateJsonCalls++
        throwIfPresent(validateJsonError)
        return validateJsonResult
    }

    override fun restoreJsonWithSettings(json: String): ExpenseBackupSettings? {
        restoreCalls++
        throwIfPresent(restoreError)
        return restoreResult
    }

    private fun throwIfPresent(error: Throwable?) {
        if (error != null) throw error
    }
}
