package com.example.monthlyexpense

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ExpenseRepository
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseQueryCharacterizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ExpenseDatabaseHelper
    private lateinit var repository: ExpenseRepository

    private val currentCount = System.getenv("EXPENSE_BENCHMARK_CURRENT")?.toInt() ?: 100
    private val archivedCount = System.getenv("EXPENSE_BENCHMARK_ARCHIVED")?.toInt() ?: 500

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = ExpenseDatabaseHelper(context)
        repository = ExpenseRepository(
            database,
            BudgetSettings(context),
            AutoBookkeepingSettings(context)
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun characterizesHomeSnapshotAndFirstAndTenthHistoryPages() = runTest {
        seedExpenses(currentCount, archivedCount)
        printQueryPlans()

        lateinit var home: ExpenseHomeSnapshot
        val homeElapsed = measureTimeMillis {
            home = repository.loadHomeSnapshot(fixtureDate, fixtureZone)
        }
        assertEquals(currentCount, home.expenses.size)
        assertDescending(home.expenses)

        lateinit var first: HistoryPage
        val firstHistoryPageElapsed = measureTimeMillis {
            first = repository.loadHistoryPage(cursor = null)
        }
        assertEquals(HISTORY_PAGE_SIZE, first.items.size)
        assertDescending(first.items)

        var tenthCursor = requireNotNull(first.nextCursor)
        repeat(8) {
            tenthCursor = requireNotNull(repository.loadHistoryPage(cursor = tenthCursor).nextCursor)
        }

        lateinit var tenth: HistoryPage
        val tenthHistoryPageElapsed = measureTimeMillis {
            tenth = repository.loadHistoryPage(cursor = tenthCursor)
        }
        assertEquals(HISTORY_PAGE_SIZE, tenth.items.size)
        assertDescending(tenth.items)
        assertTrue(
            first.items.map { it.id }.toSet()
                .intersect(tenth.items.map { it.id }.toSet())
                .isEmpty()
        )

        println(
            "QUERY_BASELINE current=$currentCount archived=$archivedCount " +
                "homeSnapshotMs=$homeElapsed firstHistoryPageMs=$firstHistoryPageElapsed " +
                "tenthHistoryPageMs=$tenthHistoryPageElapsed"
        )
    }

    private fun seedExpenses(current: Int, archived: Int) {
        database.writableDatabase.beginTransaction()
        try {
            repeat(current) { index ->
                insertFixtureExpense(
                    index = index,
                    monthKey = fixtureDate.year.toString() + "-09",
                    archived = false,
                    spentAt = currentStartMillis + (index / 3)
                )
            }
            repeat(archived) { index ->
                insertFixtureExpense(
                    index = current + index,
                    monthKey = "2026-08",
                    archived = true,
                    spentAt = archivedStartMillis + (index / 3)
                )
            }
            database.writableDatabase.setTransactionSuccessful()
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    private fun printQueryPlans() {
        printQueryPlan(
            name = "current",
            sql = "SELECT id FROM expenses WHERE month_key = ? AND archived = 0 " +
                "ORDER BY spent_at DESC, id DESC",
            args = arrayOf("2026-09")
        )
        printQueryPlan(
            name = "archived-first-page",
            sql = "SELECT id FROM expenses WHERE archived = 1 ORDER BY spent_at DESC, id DESC",
            args = emptyArray()
        )
        printQueryPlan(
            name = "archived-subsequent-page",
            sql = "SELECT id FROM expenses WHERE archived = 1 " +
                "AND (spent_at < ? OR (spent_at = ? AND id < ?)) " +
                "ORDER BY spent_at DESC, id DESC",
            args = arrayOf("1786406400100", "1786406400100", "1")
        )
        printQueryPlan(
            name = "today-total",
            sql = "SELECT COALESCE(SUM(amount_cents), 0) FROM expenses " +
                "WHERE spent_at >= ? AND spent_at < ?",
            args = arrayOf("1788998400000", "1789084800000")
        )
    }

    private fun printQueryPlan(name: String, sql: String, args: Array<String>) {
        val detail = database.readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }.joinToString(" | ")
        }
        println("QUERY_PLAN $name=$detail")
    }

    private fun insertFixtureExpense(index: Int, monthKey: String, archived: Boolean, spentAt: Long) {
        database.writableDatabase.insertOrThrow("expenses", null, ContentValues().apply {
            put("amount_cents", index + 1L)
            put("category", BuiltInCategoryKeys.FOOD)
            put("name", "fixture-$index")
            put("note", "")
            put("spent_at", spentAt)
            put("month_key", monthKey)
            put("archived", if (archived) 1 else 0)
            put("source", ExpenseSource.MANUAL.name)
        })
    }

    private fun assertDescending(records: List<ExpenseRecord>) {
        records.zipWithNext().forEach { (first, second) ->
            check(
                first.spentAt > second.spentAt ||
                    first.spentAt == second.spentAt && first.id > second.id
            ) { "Records are not ordered by spent_at DESC, id DESC" }
        }
    }

    private companion object {
        const val DATABASE_NAME = "expenses.db"
        val fixtureDate: LocalDate = LocalDate.of(2026, 9, 15)
        val fixtureZone: ZoneId = ZoneId.of("UTC")
        val currentStartMillis: Long = fixtureDate.atStartOfDay(fixtureZone).toInstant().toEpochMilli()
        const val archivedStartMillis = 1_786_406_400_000L
    }
}
