package com.example.monthlyexpense

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.categories.CategoryMutationResult
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseHomeSnapshotDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = ExpenseDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun loadHomeDataReturnsOneCoherentDatabaseSnapshotWithoutHistory() {
        val customCategory = database.categoryDao.addCustomCategory("咖啡", 0xFF795548L)
        assertTrue(customCategory is CategoryMutationResult.Success)
        val customCategories = listOf((customCategory as CategoryMutationResult.Success).category)
        val fixtureZone = ZoneId.systemDefault()
        val fixtureDate = YearMonth.now(fixtureZone).plusMonths(1).atEndOfMonth()
        val lunchTime = fixtureDate.atTime(12, 0).atZone(fixtureZone).toInstant().toEpochMilli()
        val oldTime = fixtureDate.minusMonths(1).atTime(12, 0).atZone(fixtureZone).toInstant()
            .toEpochMilli()
        val nextDayBoundary = fixtureDate.plusDays(1).atStartOfDay(fixtureZone).toInstant()
            .toEpochMilli()

        assertTrue(database.expenseDao.addExpense(1_200L, customCategories.single().key, "午饭", "", lunchTime))
        assertTrue(database.expenseDao.addExpense(3_400L, BuiltInCategoryKeys.TRAVEL, "旧行程", "", oldTime))
        assertTrue(
            database.expenseDao.addExpense(
                5_600L,
                BuiltInCategoryKeys.OTHER,
                "次日边界",
                "",
                nextDayBoundary
            )
        )
        assertTrue(database.budgetDao.setMonthlyBudget(YearMonth.from(fixtureDate), 100_000L))

        val result = database.loadHomeData(fixtureDate, fixtureZone)

        assertEquals(listOf("午饭"), result.expenses.map { it.name })
        assertEquals(customCategories, result.categories.filterNot { it.builtIn })
        assertEquals(100_000L, result.monthlyBudgetCents)
        assertEquals(1_200L, result.todayTotalCents)
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
    }
}
