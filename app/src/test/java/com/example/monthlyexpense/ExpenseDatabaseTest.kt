package com.example.monthlyexpense

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ExpenseDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: ExpenseDatabaseHelper? = null

    @Before
    fun clearDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun closeDatabase() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun upgradesVersionTwoWithoutLosingManualExpense() {
        val oldDatabase = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                spent_at INTEGER NOT NULL,
                month_key TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                source_key TEXT UNIQUE
            )""".trimIndent()
        )
        oldDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 1_280L)
            put("category", BuiltInCategoryKeys.FOOD)
            put("note", "旧账单")
            put("spent_at", 1_777_075_200_000L)
            put("month_key", "2026-04")
            put("archived", 0)
        })
        oldDatabase.version = 2
        oldDatabase.close()

        database = ExpenseDatabaseHelper(context)
        val expense = database!!.expenseDao.currentMonthRecords(LocalDate.of(2026, 4, 10)).single()

        assertEquals(1_280L, expense.amountCents)
        assertEquals(ExpenseSource.MANUAL, expense.source)
        assertNull(expense.merchant)
        assertEquals(9, database!!.readableDatabase.version)
    }

    @Test
    fun upgradesVersionThreeToDynamicCategoriesAndIndependentNames() {
        val oldDatabase = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                spent_at INTEGER NOT NULL,
                month_key TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                merchant TEXT,
                source_key TEXT UNIQUE
            )""".trimIndent()
        )
        oldDatabase.execSQL("CREATE INDEX idx_expenses_month ON expenses(month_key, archived)")
        oldDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 2_390L)
            put("category", "OTHER")
            put("note", "示例商城")
            put("spent_at", BASE_TIME)
            put("month_key", "2026-05")
            put("archived", 0)
            put("source", ExpenseSource.ALIPAY_AUTO.name)
            put("merchant", "示例商城")
            put("source_key", "alipay:key")
        })
        oldDatabase.version = 3
        oldDatabase.close()

        database = ExpenseDatabaseHelper(context)

        assertEquals(9, database!!.readableDatabase.version)
        assertEquals(
            listOf("FOOD", "ENTERTAINMENT", "TRAVEL", "SHOPPING", "BILLS", "OTHER"),
            database!!.categoryDao.categories().map { it.key }
        )
        database!!.readableDatabase.rawQuery(
            "SELECT amount_cents, category, name, note, spent_at, source, merchant, source_key FROM expenses",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2_390L, cursor.getLong(0))
            assertEquals("OTHER", cursor.getString(1))
            assertEquals("示例商城", cursor.getString(2))
            assertEquals("", cursor.getString(3))
            assertEquals(BASE_TIME, cursor.getLong(4))
            assertEquals(ExpenseSource.ALIPAY_AUTO.name, cursor.getString(5))
            assertEquals("示例商城", cursor.getString(6))
            assertEquals("alipay:key", cursor.getString(7))
        }
    }

    @Test
    fun upgradesVersionFourToQueryIndexesWithoutLosingData() {
        createVersionFourDatabaseWithExpenseAndBudget()

        database = ExpenseDatabaseHelper(context)

        assertEquals(9, database!!.readableDatabase.version)
        assertEquals(1, tableCount("expenses"))
        assertEquals(1, tableCount("categories"))
        assertEquals(1, tableCount("monthly_budgets"))
        assertEquals(
            setOf(
                "idx_expenses_archived_order",
                "idx_expenses_month_order",
                "idx_expenses_spent_at"
            ),
            expenseIndexNames()
        )
        database!!.readableDatabase.rawQuery(
            """SELECT id, amount_cents, category, name, note, spent_at, month_key, archived,
                source, merchant, source_key FROM expenses""".trimIndent(),
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(8_880L, cursor.getLong(1))
            assertEquals(BuiltInCategoryKeys.OTHER, cursor.getString(2))
            assertEquals("版本四账单", cursor.getString(3))
            assertEquals("应被保留", cursor.getString(4))
            assertEquals(BASE_TIME, cursor.getLong(5))
            assertEquals("2026-05", cursor.getString(6))
            assertEquals(0, cursor.getInt(7))
            assertEquals(ExpenseSource.ALIPAY_AUTO.name, cursor.getString(8))
            assertEquals("示例商城", cursor.getString(9))
            assertEquals("alipay:v4-key", cursor.getString(10))
            assertFalse(cursor.moveToNext())
        }
        database!!.readableDatabase.rawQuery(
            "SELECT category_key, name, color_argb, built_in, sort_order FROM categories",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(BuiltInCategoryKeys.OTHER, cursor.getString(0))
            assertEquals("其他", cursor.getString(1))
            assertEquals(0xFF58BFA6L, cursor.getLong(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(5, cursor.getInt(4))
            assertFalse(cursor.moveToNext())
        }
        database!!.readableDatabase.rawQuery(
            "SELECT month_key, amount_cents FROM monthly_budgets",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-05", cursor.getString(0))
            assertEquals(300_000L, cursor.getLong(1))
            assertFalse(cursor.moveToNext())
        }
        assertFalse(
            database!!.expenseDao.addAutomaticExpense(
                amountCents = 9_990L,
                categoryKey = BuiltInCategoryKeys.OTHER,
                name = "重复来源键",
                spentAt = BASE_TIME + 60_000L,
                source = ExpenseSource.WECHAT_AUTO,
                merchant = "另一商户",
                sourceKey = "alipay:v4-key"
            )
        )
        assertEquals(1, expenseCount())
    }

    @Test
    fun exactNotificationKeyCanOnlyCreateOneExpense() {
        database = ExpenseDatabaseHelper(context)

        val first = database!!.expenseDao.addAutomaticExpense(
            amountCents = 2_580,
            categoryKey = BuiltInCategoryKeys.OTHER,
            name = "星巴克",
            spentAt = 1_777_777_777_000,
            source = ExpenseSource.WECHAT_AUTO,
            merchant = "星巴克",
            sourceKey = "com.tencent.mm:notification-key"
        )
        val second = database!!.expenseDao.addAutomaticExpense(
            amountCents = 2_580,
            categoryKey = BuiltInCategoryKeys.OTHER,
            name = "星巴克",
            spentAt = 1_777_777_778_000,
            source = ExpenseSource.WECHAT_AUTO,
            merchant = "星巴克",
            sourceKey = "com.tencent.mm:notification-key"
        )

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, expenseCount())
    }

    @Test
    fun sameMerchantPaymentWithinThreeSecondsIsDeduplicatedAcrossKeys() {
        database = ExpenseDatabaseHelper(context)
        assertTrue(addAuto(key = "key-1", merchant = "  星巴克  ", spentAt = BASE_TIME))
        assertFalse(addAuto(key = "key-2", merchant = "星巴克", spentAt = BASE_TIME + 3_000))
        assertEquals(1, expenseCount())
    }

    @Test
    fun missingMerchantDoesNotUseFuzzyDeduplication() {
        database = ExpenseDatabaseHelper(context)
        assertTrue(addAuto(key = "key-1", merchant = null, spentAt = BASE_TIME))
        assertTrue(addAuto(key = "key-2", merchant = null, spentAt = BASE_TIME + 1_000))
        assertEquals(2, expenseCount())
    }

    @Test
    fun fuzzyDeduplicationRequiresMatchingSourceAmountAndMerchant() {
        database = ExpenseDatabaseHelper(context)
        assertTrue(addAuto(key = "base", merchant = "便利店", spentAt = BASE_TIME))
        assertTrue(addAuto(key = "source", merchant = "便利店", spentAt = BASE_TIME + 1_000, source = ExpenseSource.ALIPAY_AUTO))
        assertTrue(addAuto(key = "amount", merchant = "便利店", spentAt = BASE_TIME + 1_000, amountCents = 2_581))
        assertTrue(addAuto(key = "merchant", merchant = "另一家店", spentAt = BASE_TIME + 1_000))
        assertEquals(4, expenseCount())
    }

    @Test
    fun samePaymentAfterThreeSecondWindowIsNotSuppressed() {
        database = ExpenseDatabaseHelper(context)
        assertTrue(addAuto(key = "key-1", merchant = "星巴克", spentAt = BASE_TIME))
        assertTrue(addAuto(key = "key-2", merchant = "星巴克", spentAt = BASE_TIME + 3_001))
        assertEquals(2, expenseCount())
    }

    @Test
    fun updateExpenseChangesNameNoteAndCategoryOnly() {
        database = ExpenseDatabaseHelper(context)
        database!!.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 5_310L)
            put("category", BuiltInCategoryKeys.OTHER)
            put("name", "微信自动记账")
            put("note", "")
            put("spent_at", BASE_TIME)
            put("month_key", "2026-05")
            put("archived", 0)
            put("source", ExpenseSource.WECHAT_AUTO.name)
            put("merchant", "商户")
            put("source_key", "wechat:key")
        })
        val id = database!!.readableDatabase.rawQuery("SELECT id FROM expenses", null).use {
            it.moveToFirst()
            it.getLong(0)
        }

        assertTrue(
            database!!.expenseDao.updateExpense(
                id = id,
                name = "  日用品  ",
                note = "  家庭采购  ",
                categoryKey = BuiltInCategoryKeys.SHOPPING
            )
        )

        database!!.readableDatabase.rawQuery(
            "SELECT amount_cents, category, name, note, spent_at, source, merchant, source_key FROM expenses WHERE id = ?",
            arrayOf(id.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5_310L, cursor.getLong(0))
            assertEquals(BuiltInCategoryKeys.SHOPPING, cursor.getString(1))
            assertEquals("日用品", cursor.getString(2))
            assertEquals("家庭采购", cursor.getString(3))
            assertEquals(BASE_TIME, cursor.getLong(4))
            assertEquals(ExpenseSource.WECHAT_AUTO.name, cursor.getString(5))
            assertEquals("商户", cursor.getString(6))
            assertEquals("wechat:key", cursor.getString(7))
        }
        assertFalse(database!!.expenseDao.updateExpense(id, "", "备注", BuiltInCategoryKeys.FOOD))
        assertFalse(database!!.expenseDao.updateExpense(Long.MAX_VALUE, "名称", "", BuiltInCategoryKeys.FOOD))
    }

    @Test
    fun rejectsExpenseAmountsOutsideSupportedRange() {
        database = ExpenseDatabaseHelper(context)

        assertFalse(
            database!!.expenseDao.addExpense(
                amountCents = MoneyLimits.MAX_CENTS + 1,
                categoryKey = BuiltInCategoryKeys.OTHER,
                name = "超大金额",
                note = ""
            )
        )
        assertFalse(
            database!!.expenseDao.addAutomaticExpense(
                amountCents = MoneyLimits.MAX_CENTS + 1,
                categoryKey = BuiltInCategoryKeys.OTHER,
                name = "超大金额",
                spentAt = BASE_TIME,
                source = ExpenseSource.WECHAT_AUTO,
                merchant = null,
                sourceKey = "large:key"
            )
        )
        assertEquals(0, expenseCount())
    }

    private fun createVersionFourDatabaseWithExpenseAndBudget() {
        val oldDatabase = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        oldDatabase.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_cents INTEGER NOT NULL,
                category TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                spent_at INTEGER NOT NULL,
                month_key TEXT NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                merchant TEXT,
                source_key TEXT UNIQUE
            )""".trimIndent()
        )
        oldDatabase.execSQL("CREATE INDEX idx_expenses_month ON expenses(month_key, archived)")
        oldDatabase.execSQL(
            """CREATE TABLE categories (
                category_key TEXT PRIMARY KEY,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                color_argb INTEGER NOT NULL,
                built_in INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL
            )""".trimIndent()
        )
        oldDatabase.execSQL(
            """CREATE TABLE monthly_budgets (
                month_key TEXT PRIMARY KEY,
                amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0)
            )""".trimIndent()
        )
        oldDatabase.insertOrThrow("categories", null, ContentValues().apply {
            put("category_key", BuiltInCategoryKeys.OTHER)
            put("name", "其他")
            put("color_argb", 0xFF58BFA6L)
            put("built_in", 1)
            put("sort_order", 5)
        })
        oldDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 8_880L)
            put("category", BuiltInCategoryKeys.OTHER)
            put("name", "版本四账单")
            put("note", "应被保留")
            put("spent_at", BASE_TIME)
            put("month_key", "2026-05")
            put("archived", 0)
            put("source", ExpenseSource.ALIPAY_AUTO.name)
            put("merchant", "示例商城")
            put("source_key", "alipay:v4-key")
        })
        oldDatabase.insertOrThrow("monthly_budgets", null, ContentValues().apply {
            put("month_key", "2026-05")
            put("amount_cents", 300_000L)
        })
        oldDatabase.version = 4
        oldDatabase.close()
    }

    private fun tableCount(table: String): Int = database!!.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table", null
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun expenseIndexNames(): Set<String> = database!!.readableDatabase.rawQuery(
        "PRAGMA index_list('expenses')", null
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                cursor.getString(1).takeIf { it.startsWith("idx_expenses_") }?.let(::add)
            }
        }
    }

    private fun addAuto(
        key: String,
        merchant: String?,
        spentAt: Long,
        source: ExpenseSource = ExpenseSource.WECHAT_AUTO,
        amountCents: Long = 2_580
    ): Boolean = database!!.expenseDao.addAutomaticExpense(
        amountCents = amountCents,
        categoryKey = BuiltInCategoryKeys.OTHER,
        name = merchant ?: "自动记账",
        spentAt = spentAt,
        source = source,
        merchant = merchant,
        sourceKey = key
    )

    private fun expenseCount(): Int = database!!.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM expenses", null
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
        const val BASE_TIME = 1_777_777_777_000L
    }
}
