package com.example.monthlyexpense.backup

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class ExpenseBackupDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase("expenses.db")
        database = ExpenseDatabaseHelper(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("expenses.db")
    }

    @Test
    fun fullRestoreReplacesCurrentDatabaseWithBackup() {
        assertTrue(database.expenseDao.addExpense(1_200, BuiltInCategoryKeys.FOOD, "原账单", ""))
        database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 8), 300_000)
        val backup = database.backupDao.exportBackupDatabase()

        assertTrue(database.expenseDao.addExpense(3_400, BuiltInCategoryKeys.OTHER, "待删除账单", ""))
        database.budgetDao.setMonthlyBudget(YearMonth.of(2026, 8), 999_999)

        assertTrue(database.backupDao.restoreBackupDatabase(backup))
        assertEquals(backup, database.backupDao.exportBackupDatabase())
    }

    @Test
    fun invalidRestoreLeavesCurrentDatabaseUntouched() {
        assertTrue(database.expenseDao.addExpense(1_200, BuiltInCategoryKeys.FOOD, "应保留", ""))
        val before = database.backupDao.exportBackupDatabase()
        val invalid = before.copy(
            expenses = before.expenses.map { it.copy(categoryKey = "missing") }
        )

        assertFalse(database.backupDao.restoreBackupDatabase(invalid))
        assertEquals(before, database.backupDao.exportBackupDatabase())
    }

    @Test
    fun exportUsesCategoryNameForLegacyExpenseWithBlankNameWithoutMutatingDatabase() {
        database.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 4_730)
            put("category", BuiltInCategoryKeys.FOOD)
            put("name", "")
            put("note", "")
            put("spent_at", 1_786_636_800_000L)
            put("month_key", "2026-08")
            put("archived", 0)
            put("source", "MANUAL")
        })

        val exported = database.backupDao.exportBackupDatabase()

        assertEquals("饮食", exported.expenses.single().name)
        database.readableDatabase.rawQuery("SELECT name FROM expenses", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
    }
}
