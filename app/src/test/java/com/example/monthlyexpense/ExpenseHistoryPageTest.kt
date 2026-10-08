package com.example.monthlyexpense

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseHistoryPageTest {
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
    fun editingTimeReordersWithinDayWithoutChangingDailyTotalAndSupportsMidnight() {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        for (hour in listOf(9, 12)) {
            database.expenseDao.addExpense(100L, BuiltInCategoryKeys.FOOD, "消费$hour", "",
                today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli())
        }
        val id = database.expenseDao.currentMonthRecords().last().id
        assertTrue(database.expenseDao.updateExpense(id, "修改时间", "", BuiltInCategoryKeys.FOOD,
            time = java.time.LocalTime.of(23, 59)))
        assertEquals(id, database.expenseDao.currentMonthRecords().first().id)
        assertEquals(200L, database.expenseDao.todayTotal(today, zone))
        val past = today.minusMonths(1)
        assertTrue(database.expenseDao.updateExpense(id, "修改时间", "", BuiltInCategoryKeys.FOOD,
            date = past, time = java.time.LocalTime.MIDNIGHT))
        val archived = database.expenseDao.historyPage(null).items.single()
        assertEquals(past.atStartOfDay(), java.time.Instant.ofEpochMilli(archived.spentAt).atZone(zone).toLocalDateTime())
        assertEquals(100L, database.expenseDao.todayTotal(today, zone))
    }

    @Test
    fun changingDateReordersRecordsAndMovesTotalBetweenArchivedMonths() {
        val month = java.time.YearMonth.now().minusMonths(2)
        val zone = java.time.ZoneId.systemDefault()
        fun add(day: Int, amount: Long) {
            database.expenseDao.addExpense(amount, BuiltInCategoryKeys.FOOD, "日期$day", "",
                month.atDay(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
        }
        add(5, 100L)
        add(15, 200L)
        val first = database.expenseDao.historyPage(null, 50, month)
        val id = first.items.last().id
        database.expenseDao.updateExpense(id, "改日期", "", BuiltInCategoryKeys.FOOD, month.atDay(20))
        assertEquals(id, database.expenseDao.historyPage(null, 50, month).items.first().id)
        val otherMonth = month.minusMonths(1)
        database.expenseDao.updateExpense(id, "改日期", "", BuiltInCategoryKeys.FOOD, otherMonth.atDay(20))
        val moved = database.expenseDao.historyPage(null, 50, otherMonth)
        assertEquals(id, moved.items.single().id)
        assertEquals(listOf(200L, 100L), moved.months.map { it.totalCents })
    }

    @Test
    fun editingDateMovesExpenseBetweenTodayAndArchivedMonthAndPreservesTime() {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val past = today.minusMonths(2).withDayOfMonth(10)
        database.expenseDao.addExpense(500L, BuiltInCategoryKeys.FOOD, "日期测试", "",
            today.atTime(13, 25).atZone(zone).toInstant().toEpochMilli())
        val id = database.expenseDao.currentMonthRecords().single().id
        assertEquals(500L, database.expenseDao.todayTotal(today, zone))

        assertFalse(database.expenseDao.updateExpense(id, "日期测试", "", BuiltInCategoryKeys.FOOD, today.plusMonths(1)))
        assertEquals(id, database.expenseDao.currentMonthRecords().single().id)

        assertTrue(database.expenseDao.updateExpense(id, "日期测试", "", BuiltInCategoryKeys.FOOD, past))
        assertEquals(0L, database.expenseDao.todayTotal(today, zone))
        assertTrue(database.expenseDao.currentMonthRecords().isEmpty())
        val history = database.expenseDao.historyPage(null, 50, java.time.YearMonth.from(past))
        assertEquals(id, history.items.single().id)
        assertEquals(500L, history.months.single().totalCents)
        assertEquals(past.atTime(13, 25), java.time.Instant.ofEpochMilli(history.items.single().spentAt).atZone(zone).toLocalDateTime())

        assertTrue(database.expenseDao.updateExpense(id, "日期测试", "", BuiltInCategoryKeys.FOOD, today))
        assertEquals(500L, database.expenseDao.todayTotal(today, zone))
        assertEquals(id, database.expenseDao.currentMonthRecords().single().id)
        assertTrue(database.expenseDao.historyPage(null).months.isEmpty())
    }

    @Test
    fun monthSummaryIncludesAllPagesAndMonthFilterDoesNotLeakOtherMonths() {
        val augustIds = seedArchived(51)
        val julyId = seedArchived(1).single()
        database.writableDatabase.execSQL("UPDATE expenses SET month_key = '2026-07' WHERE id = ?", arrayOf(julyId))
        val month = java.time.YearMonth.of(2026, 8)
        val first = database.expenseDao.historyPage(null, 50, month)
        val second = database.expenseDao.historyPage(first.nextCursor, 50, month)

        assertEquals(augustIds.sortedDescending(), (first.items + second.items).map { it.id })
        assertEquals(listOf(month, java.time.YearMonth.of(2026, 7)), first.months.map { it.month })
        assertEquals((0..50).sumOf { 100L + it }, first.months.first().totalCents)
        assertEquals(51, first.months.first().count)
        assertFalse(second.hasMore)
    }

    @Test
    fun emptyHistoryHasNoItemsMorePagesOrCursor() {
        val page = database.expenseDao.historyPage(cursor = null, limit = HISTORY_PAGE_SIZE)

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun exactlyFiftyArchivedRecordsHaveNoSecondPageOrCursor() {
        val expectedIds = seedArchived(count = HISTORY_PAGE_SIZE).sortedDescending()

        val page = database.expenseDao.historyPage(cursor = null, limit = HISTORY_PAGE_SIZE)

        assertEquals(expectedIds, page.items.map { it.id })
        assertEquals(HISTORY_PAGE_SIZE, page.items.size)
        assertFalse(page.hasMore)
        assertNull(page.nextCursor)
    }

    @Test
    fun fiftyOneArchivedRecordsExposeOneRemainingRecordThroughCursor() {
        val expectedIds = seedArchived(count = HISTORY_PAGE_SIZE + 1).sortedDescending()

        val first = database.expenseDao.historyPage(cursor = null, limit = HISTORY_PAGE_SIZE)
        val second = database.expenseDao.historyPage(cursor = first.nextCursor, limit = HISTORY_PAGE_SIZE)

        assertEquals(expectedIds.take(HISTORY_PAGE_SIZE), first.items.map { it.id })
        assertEquals(HISTORY_PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(first.items.last().spentAt, first.nextCursor!!.spentAt)
        assertEquals(first.items.last().id, first.nextCursor!!.id)
        assertEquals(expectedIds.drop(HISTORY_PAGE_SIZE), second.items.map { it.id })
        assertFalse(second.hasMore)
        assertNull(second.nextCursor)
    }

    @Test
    fun repeatedTimestampsHaveNoDuplicateOrMissingIdsAcrossPages() {
        val expectedIds = seedArchived(count = 75, sameSpentAt = FIXTURE_TIME)
            .sortedDescending()
        val first = database.expenseDao.historyPage(cursor = null, limit = HISTORY_PAGE_SIZE)
        val second = database.expenseDao.historyPage(cursor = first.nextCursor, limit = HISTORY_PAGE_SIZE)

        assertEquals(expectedIds, (first.items + second.items).map { it.id })
        assertEquals(HISTORY_PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertFalse(second.hasMore)
        assertNull(second.nextCursor)
    }

    @Test
    fun deletingFirstPageRowDoesNotShiftOrDuplicateRemainingRows() {
        val expectedIds = seedArchived(count = 75, sameSpentAt = FIXTURE_TIME)
            .sortedDescending()
        val first = database.expenseDao.historyPage(cursor = null, limit = HISTORY_PAGE_SIZE)
        database.expenseDao.delete(first.items.first().id)

        val second = database.expenseDao.historyPage(cursor = first.nextCursor, limit = HISTORY_PAGE_SIZE)

        assertEquals(expectedIds.drop(HISTORY_PAGE_SIZE), second.items.map { it.id })
        assertTrue((first.items.map { it.id } intersect second.items.map { it.id }.toSet()).isEmpty())
        assertFalse(second.hasMore)
        assertNull(second.nextCursor)
    }

    private fun seedArchived(count: Int, sameSpentAt: Long? = null): List<Long> =
        List(count) { index ->
            database.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
                put("amount_cents", 100L + index)
                put("category", BuiltInCategoryKeys.FOOD)
                put("name", "历史账单$index")
                put("note", "")
                put("spent_at", sameSpentAt ?: FIXTURE_TIME + index)
                put("month_key", "2026-08")
                put("archived", 1)
                put("source", ExpenseSource.MANUAL.name)
            })
        }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
        const val FIXTURE_TIME = 1_788_220_800_000L
    }
}
