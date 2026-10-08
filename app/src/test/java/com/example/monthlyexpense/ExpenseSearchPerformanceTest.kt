package com.example.monthlyexpense

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpenseSearchPerformanceTest {
    @Test fun boundedFirstPagesAndExactSummaryForTenAndFiftyThousandRows() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (count in listOf(10_000, 50_000)) {
            context.deleteDatabase("expenses.db")
            try {
                ExpenseDatabaseHelper(context).use { database ->
                    ExpenseSearchPerformanceFixture.seed(database, count)
                    val measurements = ExpenseSearchPerformanceFixture.measure(database)
                    assertEquals(count.toLong(), measurements.first { it.case == "all" }.matches)
                    assertEquals((count / 2).toLong(), measurements.first { it.case == "dense_chinese" }.matches)
                    assertEquals((count / 100).toLong(), measurements.first { it.case == "sparse_chinese" }.matches)
                    assertEquals(0L, measurements.first { it.case == "no_match" }.matches)
                    measurements.forEach { println("JVM_ONLY rows=$count $it") }
                }
            } finally { context.deleteDatabase("expenses.db") }
        }
    }
}
