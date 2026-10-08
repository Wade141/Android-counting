package com.example.monthlyexpense.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseRepositoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun searchReadWaitsForIoDispatcherAndDoesNotWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, dispatcher)
        val query = (com.example.monthlyexpense.search.validateSearch("",
            com.example.monthlyexpense.search.ExpenseSearchFilters(), LocalDate.now(), ZoneId.systemDefault())
            as com.example.monthlyexpense.search.SearchValidationResult.Valid).query
        val read = async(UnconfinedTestDispatcher(testScheduler)) { repository.loadSearchPage(query, null) }
        assertFalse(read.isCompleted)
        runCurrent()
        assertEquals(0L, read.await().summary!!.count)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun readOnlyHomeProjectsBudgetWithoutArchivingOrCreatingRows() = runTest {
        val date = LocalDate.of(2099, 8, 20)
        val zone = ZoneId.systemDefault()
        database.budgetDao.setMonthlyBudget(YearMonth.from(date).minusMonths(1), 12_300L)
        database.expenseDao.addExpense(900L, BuiltInCategoryKeys.OTHER, "旧月账目", "",
            date.minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
        database.expenseDao.addExpense(1_600L, BuiltInCategoryKeys.OTHER, "当月账目", "",
            date.atStartOfDay(zone).toInstant().toEpochMilli())
        database.writableDatabase.execSQL("CREATE TRIGGER forbid_archive BEFORE UPDATE ON expenses BEGIN SELECT RAISE(ABORT, 'read-only ledger'); END")
        database.writableDatabase.execSQL("CREATE TRIGGER forbid_budget BEFORE INSERT ON monthly_budgets BEGIN SELECT RAISE(ABORT, 'read-only budget'); END")
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, UnconfinedTestDispatcher(testScheduler))
        val snapshot = repository.loadReadOnlyHomeSnapshot(date, zone)
        assertEquals(listOf("当月账目"), snapshot.expenses.map { it.name })
        assertEquals(1_600L, snapshot.todayTotalCents)
        assertEquals(12_300L, snapshot.monthlyBudgetCents)
        database.readableDatabase.rawQuery("SELECT count(*) FROM monthly_budgets", null).use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        database.readableDatabase.rawQuery("SELECT archived FROM expenses WHERE name = '旧月账目'", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedSourcePolicyInitializationCannotWritePreferenceFallbackOrPublishSuccess() = runTest {
        val changes = mutableListOf<Set<DataDomain>>()
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, UnconfinedTestDispatcher(testScheduler),
            initializeSourcePolicy = { error("source policy storage unavailable") }, onChanged = changes::add)
        assertTrue(runCatching { repository.setAutoBookkeepingEnabled(true) }.isFailure)
        assertTrue(runCatching { repository.setWeChatEnabled(false) }.isFailure)
        assertTrue(runCatching { repository.setAlipayEnabled(false) }.isFailure)
        assertFalse(autoSettings.isEnabled)
        assertTrue(autoSettings.isWeChatEnabled)
        assertTrue(autoSettings.isAlipayEnabled)
        assertTrue(changes.isEmpty())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun ledgerOnlyRefreshPreservesSettingsAndReviewOnlyReadsNothing() = runTest {
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, UnconfinedTestDispatcher(testScheduler))
        val date = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val initial = repository.loadHomeSnapshot(date, zone)
        budgetSettings.dailyBudgetCents = initial.dailyBudgetCents + 1000
        database.expenseDao.addExpense(1234, BuiltInCategoryKeys.OTHER, "新交易", "")
        val updated = repository.refreshHomeSnapshot(date, zone, initial, setOf(DataDomain.LEDGER))
        assertEquals(initial.dailyBudgetCents, updated.dailyBudgetCents)
        assertEquals(1234L, updated.todayTotalCents)
        assertEquals(initial.categories, updated.categories)
        val reviewOnly = repository.refreshHomeSnapshot(date, zone, updated, setOf(DataDomain.REVIEW))
        assertTrue(updated === reviewOnly)
        val settings = repository.refreshHomeSnapshot(date, zone, updated, setOf(DataDomain.SETTINGS))
        assertEquals(initial.dailyBudgetCents + 1000, settings.dailyBudgetCents)
        assertTrue(updated.expenses === settings.expenses)
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper
    private lateinit var budgetSettings: BudgetSettings
    private lateinit var autoSettings: AutoBookkeepingSettings

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        clearPreferences()
        database = ExpenseDatabaseHelper(context)
        budgetSettings = BudgetSettings(context)
        autoSettings = AutoBookkeepingSettings(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        clearPreferences()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadHomeSnapshotRunsOnInjectedDispatcherAndAttachesSettingsWithoutArchivedHistory() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, ioDispatcher)
        val zone = ZoneId.systemDefault()
        val fixtureMonth = YearMonth.now(zone).plusMonths(1)
        val now = fixtureMonth.atEndOfMonth()
        val currentTime = now.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val oldTime = now.minusMonths(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        database.expenseDao.addExpense(1_200L, BuiltInCategoryKeys.FOOD, "午饭", "", currentTime)
        database.expenseDao.addExpense(3_400L, BuiltInCategoryKeys.TRAVEL, "旧行程", "", oldTime)
        database.budgetDao.setMonthlyBudget(YearMonth.from(now), 100_000L)
        budgetSettings.dailyBudgetCents = 5_000L
        autoSettings.isEnabled = true
        autoSettings.isWeChatEnabled = false
        autoSettings.isAlipayEnabled = true

        val result = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.loadHomeSnapshot(now, zone)
        }
        assertFalse(result.isCompleted)

        runCurrent()
        val snapshot = result.await()
        assertEquals(listOf("午饭"), snapshot.expenses.map { it.name })
        assertEquals(100_000L, snapshot.monthlyBudgetCents)
        assertEquals(5_000L, snapshot.dailyBudgetCents)
        assertEquals(1_200L, snapshot.todayTotalCents)
        assertTrue(snapshot.autoBookkeepingEnabled)
        assertFalse(snapshot.weChatEnabled)
        assertTrue(snapshot.alipayEnabled)
        assertFalse(snapshot::class.java.declaredFields.any { it.name == "archived" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadHistoryPageRunsOnInjectedDispatcherAndForwardsCursorAndLimitExactly() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val repository = ExpenseRepository(database, budgetSettings, autoSettings, ioDispatcher)
        val cursor = HistoryCursor(spentAt = 123L, id = 7L)
        database.writableDatabase.insertOrThrow("expenses", null, android.content.ContentValues().apply {
            put("id", 6L)
            put("amount_cents", 100L)
            put("category", BuiltInCategoryKeys.FOOD)
            put("name", "匹配游标")
            put("note", "")
            put("spent_at", 123L)
            put("month_key", "2026-08")
            put("archived", 1)
            put("source", "MANUAL")
        })
        database.writableDatabase.insertOrThrow("expenses", null, android.content.ContentValues().apply {
            put("id", 5L)
            put("amount_cents", 200L)
            put("category", BuiltInCategoryKeys.FOOD)
            put("name", "第二条")
            put("note", "")
            put("spent_at", 122L)
            put("month_key", "2026-08")
            put("archived", 1)
            put("source", "MANUAL")
        })
        val expected = database.expenseDao.historyPage(cursor, limit = 1)

        val result = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.loadHistoryPage(cursor, limit = 1)
        }
        assertFalse(result.isCompleted)

        runCurrent()
        assertEquals(expected, result.await())
        assertEquals(listOf(6L), expected.items.map { it.id })
        assertTrue(expected.hasMore)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun manualExpenseMutationsPreserveDatabaseValidation() = runTest {
        val repository = ExpenseRepository(
            database, budgetSettings, autoSettings, StandardTestDispatcher(testScheduler)
        )

        assertTrue(repository.addExpense(2_500L, BuiltInCategoryKeys.FOOD, "晚饭", "朋友聚餐"))
        val inserted = database.expenseDao.currentMonthRecords().single()
        assertTrue(repository.updateExpense(inserted.id, "晚餐", "", BuiltInCategoryKeys.OTHER))
        assertEquals("晚餐", database.expenseDao.currentMonthRecords().single().name)

        repository.deleteExpense(inserted.id)
        assertTrue(database.expenseDao.currentMonthRecords().isEmpty())
        assertFalse(repository.addExpense(0L, BuiltInCategoryKeys.FOOD, "无效", ""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun categoryBudgetAndSettingsMutationsKeepExistingResultContracts() = runTest {
        val repository = ExpenseRepository(
            database, budgetSettings, autoSettings, StandardTestDispatcher(testScheduler)
        )

        val added = repository.addCustomCategory("咖啡", 0xFF795548L)
        assertTrue(added is CategoryMutationResult.Success)
        val category = (added as CategoryMutationResult.Success).category
        assertTrue(
            repository.updateCategory(category.key, "咖啡茶饮", 0xFF6D4C41L) is
                CategoryMutationResult.Success
        )
        assertEquals(CategoryDeleteResult.Deleted, repository.deleteCustomCategory(category.key))

        val currentMonth = YearMonth.now()
        assertTrue(repository.setMonthlyBudget(currentMonth, 88_000L))
        repository.setDailyBudget(6_000L)
        repository.setAutoBookkeepingEnabled(true)
        repository.setWeChatEnabled(false)
        repository.setAlipayEnabled(true)

        assertEquals(88_000L, database.budgetDao.getOrCreateMonthlyBudget(currentMonth))
        assertEquals(6_000L, budgetSettings.dailyBudgetCents)
        assertTrue(autoSettings.isEnabled)
        assertFalse(autoSettings.isWeChatEnabled)
        assertTrue(autoSettings.isAlipayEnabled)
    }

    private fun clearPreferences() {
        context.getSharedPreferences(BudgetSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(AutoBookkeepingSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
    }
}
