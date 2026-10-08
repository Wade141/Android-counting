package com.example.monthlyexpense.budget

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.MoneyLimits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class BudgetStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase("expenses.db")
        context.getSharedPreferences(BudgetSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = ExpenseDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun monthlyBudgetCopiesLatestPriorMonthOnlyOnce() {
        assertEquals(0L, database.budgetDao.getOrCreateMonthlyBudget(YearMonth.of(2026, 1)))
        assertTrue(database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 1), 100_000))
        assertEquals(100_000L, database.budgetDao.getOrCreateMonthlyBudget(YearMonth.of(2026, 2)))
        assertTrue(database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 1), 200_000))
        assertEquals(100_000L, database.budgetDao.getOrCreateMonthlyBudget(YearMonth.of(2026, 2)))
        assertFalse(database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 3), -1))
        assertFalse(database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 3), MoneyLimits.MAX_CENTS + 1))
    }

    @Test
    fun dailyBudgetPersistsAndTodayTotalUsesLocalDayBounds() {
        val settings = BudgetSettings(context)
        assertEquals(0L, settings.dailyBudgetCents)
        settings.dailyBudgetCents = 5_000
        assertEquals(5_000L, BudgetSettings(context).dailyBudgetCents)
        assertThrows(IllegalArgumentException::class.java) {
            settings.dailyBudgetCents = MoneyLimits.MAX_CENTS + 1
        }

        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 8, 23)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        insertExpenseAt(start - 1, 100)
        insertExpenseAt(start, 200)
        insertExpenseAt(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, 300)
        insertExpenseAt(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), 400)

        assertEquals(500L, database.expenseDao.todayTotal(date, zone))
    }

    private fun insertExpenseAt(spentAt: Long, cents: Long) {
        database.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", cents)
            put("category", BuiltInCategoryKeys.OTHER)
            put("name", "测试")
            put("note", "")
            put("spent_at", spentAt)
            put("month_key", "2026-08")
            put("archived", 0)
            put("source", "MANUAL")
        })
    }
}
