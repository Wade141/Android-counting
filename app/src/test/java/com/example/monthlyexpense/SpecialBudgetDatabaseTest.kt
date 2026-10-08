package com.example.monthlyexpense

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.backup.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SpecialBudgetDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ExpenseDatabaseHelper
    @Before fun setup() { context.deleteDatabase("expenses.db"); db = ExpenseDatabaseHelper(context) }
    @After fun cleanup() { db.close(); context.deleteDatabase("expenses.db") }

    @Test fun specialExpenseIsExcludedFromDailyAndMonthlyBudgetButRemainsInLedger() {
        db.expenseDao.addExpense(132_377, "FOOD", "生活费", "")
        db.expenseDao.addExpense(6_021_680, "OTHER", "学费", "")
        db.writableDatabase.execSQL("UPDATE expenses SET is_special = 1 WHERE name = '学费'")
        val snapshot = db.loadHomeData(LocalDate.now(), ZoneId.systemDefault())
        assertEquals(2, snapshot.expenses.size)
        assertEquals(132_377L, snapshot.todayTotalCents)
        val totals = ExpenseTotals.from(snapshot.expenses)
        assertEquals(132_377L, totals.monthlyCents)
        assertEquals(6_021_680L, totals.specialCents)
        assertEquals(6_154_057L, totals.allCents)
        assertEquals(mapOf("FOOD" to 132_377L), totals.byCategory)
        assertEquals(6_154_057L, snapshot.expenses.sumOf { it.amountCents })
    }

    @Test fun movingAndEditingPreservesIdentityAndCanBeUndone() {
        db.expenseDao.addExpense(6_021_680, "OTHER", "学费", "原备注")
        val original = db.expenseDao.currentMonthRecords().single()
        val education = db.categoryDao.addCustomCategory("教育", 0xFF123456L)
            as com.example.monthlyexpense.categories.CategoryMutationResult.Success
        assertTrue(db.expenseDao.updateExpense(original.id, "大学学费", "本学年", education.category.key, isSpecial = true))
        db.close()
        db = ExpenseDatabaseHelper(context)
        val moved = db.expenseDao.currentMonthRecords().single()
        assertTrue(moved.isSpecial)
        assertEquals(original.id, moved.id)
        assertEquals(original.amountCents, moved.amountCents)
        assertEquals(original.spentAt, moved.spentAt)
        assertEquals(education.category.key, moved.category.key)
        assertEquals("本学年", moved.note)
        assertFalse(db.expenseDao.updateExpense(original.id, "", "无效", "OTHER", isSpecial = false))
        assertEquals(moved, db.expenseDao.currentMonthRecords().single())
        assertTrue(db.expenseDao.updateExpense(original.id, "大学学费", "更新备注", education.category.key))
        assertTrue(db.expenseDao.currentMonthRecords().single().isSpecial)
        assertTrue(db.expenseDao.updateExpense(original.id, "大学学费", "更新备注", education.category.key, isSpecial = false))
        assertFalse(db.expenseDao.currentMonthRecords().single().isSpecial)
        assertEquals(6_021_680L, db.expenseDao.todayTotal(LocalDate.now(), ZoneId.systemDefault()))
    }

    @Test fun previousBackupVersionsDefaultToDailyAndNewBackupRequiresBooleanMembership() {
        db.expenseDao.addExpense(100, "FOOD", "早餐", "")
        val document = ExpenseBackupDocument(1, db.backupDao.exportBackupDatabase(),
            ExpenseBackupSettings(0, false, false, false))
        val encoded = ExpenseBackupJson.encode(document)
        for (version in 1..2) {
            val root = org.json.JSONObject(encoded).put("version", version)
            val database = root.getJSONObject("database")
            val row = database.getJSONArray("expenses").getJSONObject(0)
            row.remove("isSpecial")
            if (version == 1) {
                database.remove("categoryRules")
                row.remove("classificationOrigin")
                row.remove("classificationHits")
            }
            val decoded = ExpenseBackupJson.decode(root.toString())
            assertFalse(decoded.database.expenses.single().isSpecial)
            assertTrue(db.backupDao.restoreBackupDatabase(decoded.database))
            assertEquals(100L, db.expenseDao.todayTotal(LocalDate.now(), ZoneId.systemDefault()))
        }
        val malformed = org.json.JSONObject(encoded)
        malformed.getJSONObject("database").getJSONArray("expenses").getJSONObject(0).put("isSpecial", "true")
        assertThrows(InvalidExpenseBackupException::class.java) { ExpenseBackupJson.decode(malformed.toString()) }
    }

    @Test fun versionEightMigrationPreservesExistingRecordsAndDefaultsToDaily() {
        db.expenseDao.addExpense(6_021_680, "OTHER", "学费", "原备注")
        val original = db.expenseDao.currentMonthRecords().single()
        val sql = db.writableDatabase
        sql.execSQL("""CREATE TABLE expenses_v8 (
            id INTEGER PRIMARY KEY AUTOINCREMENT, amount_cents INTEGER NOT NULL, category TEXT NOT NULL,
            name TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', spent_at INTEGER NOT NULL,
            month_key TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL DEFAULT 'MANUAL', merchant TEXT, source_key TEXT UNIQUE,
            classification_origin TEXT NOT NULL DEFAULT 'LEGACY', classification_match_json TEXT)""")
        sql.execSQL("INSERT INTO expenses_v8 SELECT id, amount_cents, category, name, note, spent_at, month_key, archived, source, merchant, source_key, classification_origin, classification_match_json FROM expenses")
        sql.execSQL("DROP TABLE expenses")
        sql.execSQL("ALTER TABLE expenses_v8 RENAME TO expenses")
        sql.version = 8
        db.close()
        db = ExpenseDatabaseHelper(context)
        assertEquals(original, db.expenseDao.currentMonthRecords().single())
        assertTrue(db.expenseDao.updateExpense(original.id, original.name, original.note, "OTHER", isSpecial = true))
        assertEquals(0L, db.expenseDao.todayTotal(LocalDate.now(), ZoneId.systemDefault()))
    }

    @Test fun specialExpensesRemainSpecialInHistoryAndSearch() {
        db.expenseDao.addExpense(6_021_680, "OTHER", "学费", "")
        val id = db.expenseDao.currentMonthRecords().single().id
        assertTrue(db.expenseDao.updateExpense(id, "学费", "旧学期", "OTHER", LocalDate.now().minusMonths(1), isSpecial = true))
        assertTrue(db.expenseDao.currentMonthRecords().isEmpty())
        assertTrue(db.expenseDao.historyPage(null).items.single().isSpecial)
        val query = com.example.monthlyexpense.search.validateSearch("学费",
            com.example.monthlyexpense.search.ExpenseSearchFilters(), LocalDate.now(), ZoneId.systemDefault())
            as com.example.monthlyexpense.search.SearchValidationResult.Valid
        val result = db.expenseDao.searchPage(query.query, null)
        assertTrue(result.items.single().isSpecial)
        assertEquals(6_021_680L, result.summary!!.totalCents)
    }

    @Test fun backupRestorePreservesSpecialBudgetMembership() {
        db.expenseDao.addExpense(6_021_680, "BILLS", "学费", "本学年")
        db.writableDatabase.execSQL("UPDATE expenses SET is_special = 1")
        val backup = ExpenseBackupDocument(1, db.backupDao.exportBackupDatabase(),
            ExpenseBackupSettings(0, false, false, false))
        val restored = ExpenseBackupJson.decode(ExpenseBackupJson.encode(backup))
        assertTrue(db.backupDao.restoreBackupDatabase(restored.database))
        db.readableDatabase.rawQuery("SELECT is_special, note, category FROM expenses", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals("本学年", it.getString(1))
            assertEquals("BILLS", it.getString(2))
        }
        assertEquals(0L, db.expenseDao.todayTotal(LocalDate.now(), ZoneId.systemDefault()))
    }
}
