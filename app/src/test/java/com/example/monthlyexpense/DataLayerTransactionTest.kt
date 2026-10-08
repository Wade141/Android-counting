package com.example.monthlyexpense

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.categories.CategoryMutationResult
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataLayerTransactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: ExpenseDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase("expenses.db")
        helper = ExpenseDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        if (::helper.isInitialized) helper.close()
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun categoryExpenseAndBudgetWritesParticipateInTheSameRollback() {
        val before = helper.backupDao.exportBackupDatabase()
        val month = YearMonth.now().plusMonths(1)
        val spentAt = month.atDay(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val category = helper.categoryDao.addCustomCategory("共享事务", 0xFF123456L)
                as CategoryMutationResult.Success
            assertTrue(helper.expenseDao.addExpense(1234, category.category.key, "支出", "", spentAt))
            assertTrue(helper.budgetDao.setMonthlyBudget(month, 5000))
            val during = helper.backupDao.exportBackupDatabase()
            assertEquals(1, during.expenses.size)
            assertEquals(category.category.key, during.expenses.single().categoryKey)
            assertEquals(5000L, during.monthlyBudgets.single().amountCents)
            // Deliberately leave the outer transaction unsuccessful.
        } finally {
            db.endTransaction()
        }
        assertEquals(before, helper.backupDao.exportBackupDatabase())
    }

    @Test
    fun failedRestoreRollsBackAllTablesAfterExpensesHaveBeenReplaced() {
        assertTrue(helper.expenseDao.addExpense(1200, BuiltInCategoryKeys.FOOD, "原账单", ""))
        assertTrue(helper.budgetDao.setMonthlyBudget(YearMonth.now(), 5000))
        val before = helper.backupDao.exportBackupDatabase()
        val replacement = before.copy(
            expenses = before.expenses.map { it.copy(name = "替换账单", amountCents = 3400) },
            categories = before.categories.map { it.copy(colorArgb = 0xFF123456L) },
            monthlyBudgets = before.monthlyBudgets.map { it.copy(amountCents = 9000) }
        )
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore_budget BEFORE INSERT ON monthly_budgets " +
                "BEGIN SELECT RAISE(ABORT, 'restore unavailable'); END"
        )
        assertFalse(helper.backupDao.restoreBackupDatabase(replacement))
        assertEquals(before, helper.backupDao.exportBackupDatabase())
    }

    @Test
    fun failedHomeBudgetCreationRollsBackEarlierArchiving() {
        val month = YearMonth.now().plusMonths(2)
        val zone = ZoneId.systemDefault()
        val spentAt = month.minusMonths(1).atDay(2).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(helper.expenseDao.addExpense(1200, BuiltInCategoryKeys.FOOD, "午饭", "", spentAt))
        val before = helper.backupDao.exportBackupDatabase()
        assertFalse(before.expenses.single().archived)
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_budget BEFORE INSERT ON monthly_budgets " +
                "BEGIN SELECT RAISE(ABORT, 'budget unavailable'); END"
        )
        assertThrows(SQLiteException::class.java) {
            helper.loadHomeData(month.atDay(1), zone)
        }
        assertEquals(before, helper.backupDao.exportBackupDatabase())

        helper.writableDatabase.execSQL("DROP TRIGGER reject_budget")
        val home = helper.loadHomeData(month.atDay(1), zone)
        assertTrue(home.expenses.isEmpty())
        assertEquals(0L, home.monthlyBudgetCents)
        assertTrue(helper.backupDao.exportBackupDatabase().expenses.single().archived)
    }
}
