package com.example.monthlyexpense

import com.example.monthlyexpense.search.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Only call with a disposable test database. Seed and cases are shared by JVM and device runs. */
object ExpenseSearchPerformanceFixture {
    private val today = LocalDate.of(2026, 9, 30)
    private val zone = ZoneId.of("Asia/Shanghai")

    fun seed(database: ExpenseDatabaseHelper, count: Int) {
        val random = Random(20260930)
        val db = database.writableDatabase
        check(db.rawQuery("SELECT COUNT(*) FROM expenses", null).use { it.moveToFirst(); it.getLong(0) == 0L })
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO expenses(amount_cents,category,name,note,merchant,spent_at,month_key,archived,source) VALUES(?,?,?,?,?,?,?,?,?)").use { statement ->
                repeat(count) { i ->
                    val date = today.minusDays(random.nextInt(730).toLong())
                    statement.bindLong(1, random.nextLong(1, 500_000))
                    statement.bindString(2, listOf("FOOD", "SHOPPING", "OTHER")[random.nextInt(3)])
                    statement.bindString(3, if (i % 100 == 0) "稀疏咖啡" else "消费$i")
                    statement.bindString(4, if (i % 2 == 0) "日常支出" else "")
                    statement.bindString(5, "测试商户${i % 20}")
                    statement.bindLong(6, date.atStartOfDay(zone).toInstant().toEpochMilli() + random.nextLong(86_400_000))
                    statement.bindString(7, date.toString().take(7))
                    statement.bindLong(8, if (date.month == today.month && date.year == today.year) 0 else 1)
                    statement.bindString(9, ExpenseSource.entries[random.nextInt(4)].name)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun cases(): List<Pair<String, ExpenseSearchQuery>> = listOf(
        Triple("all", "", ExpenseSearchFilters()),
        Triple("date", "", ExpenseSearchFilters(SearchDatePreset.THIS_MONTH)),
        Triple("category_date", "", ExpenseSearchFilters(SearchDatePreset.THIS_MONTH, categoryKeys = setOf("FOOD"))),
        Triple("source_amount", "", ExpenseSearchFilters(minAmountText = "10", maxAmountText = "200", sources = setOf(ExpenseSource.WECHAT_AUTO))),
        Triple("dense_chinese", "日常", ExpenseSearchFilters()),
        Triple("sparse_chinese", "咖啡", ExpenseSearchFilters()),
        Triple("no_match", "从未出现", ExpenseSearchFilters())
    ).map { (name, word, filters) -> name to (validateSearch(word, filters, today, zone) as SearchValidationResult.Valid).query }

    data class Measurement(val case: String, val coldMs: Double, val p50Ms: Double, val p95Ms: Double,
        val loaded: Int, val matches: Long)

    fun measure(database: ExpenseDatabaseHelper): List<Measurement> = cases().map { (name, query) ->
        var page: ExpenseSearchPage? = null
        fun once(): Double {
            val start = System.nanoTime()
            page = database.expenseDao.searchPage(query, null)
            check(page!!.items.size <= 50)
            return (System.nanoTime() - start) / 1_000_000.0
        }
        val cold = once()
        repeat(5) { once() }
        val samples = List(30) { once() }.sorted()
        Measurement(name, cold, samples[14], samples[28], page!!.items.size, page!!.summary!!.count)
    }
}
