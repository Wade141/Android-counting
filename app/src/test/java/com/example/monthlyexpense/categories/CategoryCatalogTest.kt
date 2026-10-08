package com.example.monthlyexpense.categories

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryCatalogTest {
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
    fun addsRenamesAndRecolorsCustomCategory() {
        val added = database.categoryDao.addCustomCategory("  健身  ", 0xFF123456L)
        assertTrue(added is CategoryMutationResult.Success)
        val key = (added as CategoryMutationResult.Success).category.key
        assertTrue(key.startsWith("custom:"))
        assertEquals("健身", added.category.name)
        assertEquals(0xFF123456L, added.category.colorArgb)

        val updated = database.categoryDao.updateCategoryDefinition(key, "运动", 0xFFAABBCCL)
        assertTrue(updated is CategoryMutationResult.Success)
        assertEquals("运动", (updated as CategoryMutationResult.Success).category.name)
        assertEquals(0xFFAABBCCL, updated.category.colorArgb)
    }

    @Test
    fun rejectsInvalidAndDuplicateNamesIgnoringCase() {
        assertEquals(CategoryMutationResult.InvalidName, database.categoryDao.addCustomCategory("   ", 0xFFFFFFFFL))
        assertEquals(CategoryMutationResult.InvalidName, database.categoryDao.addCustomCategory("123456789012345678901", 0xFFFFFFFFL))
        assertTrue(database.categoryDao.addCustomCategory("Health", 0xFFFFFFFFL) is CategoryMutationResult.Success)
        assertEquals(CategoryMutationResult.DuplicateName, database.categoryDao.addCustomCategory(" health ", 0xFF000000L))
    }

    @Test
    fun builtInCategoryOnlyAllowsColorChange() {
        assertEquals(
            CategoryMutationResult.BuiltInNameLocked,
            database.categoryDao.updateCategoryDefinition(BuiltInCategoryKeys.FOOD, "餐饮", 0xFF010203L)
        )
        val recolored = database.categoryDao.updateCategoryDefinition(BuiltInCategoryKeys.FOOD, null, 0xFF010203L)
        assertTrue(recolored is CategoryMutationResult.Success)
        assertEquals(0xFF010203L, (recolored as CategoryMutationResult.Success).category.colorArgb)
        assertEquals(CategoryDeleteResult.BuiltInLocked, database.categoryDao.deleteCustomCategory(BuiltInCategoryKeys.FOOD))
    }

    @Test
    fun deletesUnusedCustomCategoryButRejectsReferencedCategory() {
        val unused = database.categoryDao.addCustomCategory("未使用", 0xFF111111L) as CategoryMutationResult.Success
        assertEquals(CategoryDeleteResult.Deleted, database.categoryDao.deleteCustomCategory(unused.category.key))

        val used = database.categoryDao.addCustomCategory("正在使用", 0xFF222222L) as CategoryMutationResult.Success
        database.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", 100L)
            put("category", used.category.key)
            put("name", "测试")
            put("note", "")
            put("spent_at", 1_777_777_777_000L)
            put("month_key", "2026-05")
            put("archived", 0)
            put("source", "MANUAL")
        })

        assertEquals(CategoryDeleteResult.InUse(1), database.categoryDao.deleteCustomCategory(used.category.key))
        assertEquals(1, database.categoryDao.categoryUsageCount(used.category.key))
        assertTrue(database.categoryDao.categories().any { it.key == used.category.key })
    }
}
