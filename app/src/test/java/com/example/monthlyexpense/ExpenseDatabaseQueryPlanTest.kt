package com.example.monthlyexpense

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseDatabaseQueryPlanTest {
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
    fun currentMonthOrderedRecordsUseMonthOrderIndex() {
        assertUsesIndex(
            sql = "SELECT id FROM expenses WHERE month_key = ? AND archived = 0 " +
                "ORDER BY spent_at DESC, id DESC",
            args = arrayOf("2026-09"),
            expectedIndex = "idx_expenses_month_order"
        )
    }

    @Test
    fun firstHistoryPageUsesArchivedOrderIndex() {
        assertUsesIndex(
            sql = "SELECT id FROM expenses WHERE archived = 1 ORDER BY spent_at DESC, id DESC",
            args = emptyArray(),
            expectedIndex = "idx_expenses_archived_order"
        )
    }

    @Test
    fun subsequentHistoryPageUsesArchivedOrderIndex() {
        assertUsesIndex(
            sql = "SELECT id FROM expenses WHERE archived = 1 " +
                "AND (spent_at < ? OR (spent_at = ? AND id < ?)) " +
                "ORDER BY spent_at DESC, id DESC",
            args = arrayOf("1786406400100", "1786406400100", "1"),
            expectedIndex = "idx_expenses_archived_order"
        )
    }

    @Test
    fun todayRangeAggregateUsesSpentAtIndex() {
        assertUsesIndex(
            sql = "SELECT COALESCE(SUM(amount_cents), 0) FROM expenses " +
                "WHERE spent_at >= ? AND spent_at < ?",
            args = arrayOf("1788998400000", "1789084800000"),
            expectedIndex = "idx_expenses_spent_at"
        )
    }

    @Test fun searchDateRangeUsesExistingTimeIndex() {
        assertUsesIndex("SELECT e.id FROM expenses e WHERE e.spent_at >= ? AND e.spent_at < ? " +
            "ORDER BY e.spent_at DESC, e.id DESC LIMIT 51", arrayOf("1788998400000", "1791676800000"),
            "idx_expenses_spent_at")
    }

    @Test fun unfilteredSearchUsesExistingTimeIndex() {
        assertUsesIndex("SELECT e.id FROM expenses e ORDER BY e.spent_at DESC, e.id DESC LIMIT 51",
            emptyArray(), "idx_expenses_spent_at")
    }

    private fun assertUsesIndex(sql: String, args: Array<String>, expectedIndex: String) {
        val detail = database.readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }.joinToString(" | ")
        }

        assertTrue(
            "Expected query plan to use $expectedIndex, but was: $detail",
            detail.contains(expectedIndex)
        )
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
    }
}
