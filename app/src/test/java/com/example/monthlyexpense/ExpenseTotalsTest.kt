package com.example.monthlyexpense

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseTotalsTest {
    @Test fun totalsUseCategoryKeysAndLongCents() {
        val food = ExpenseCategory("food", "餐饮", 0, true, 0)
        val renamed = food.copy(name = "新名称")
        fun record(id: Long, amount: Long, category: ExpenseCategory) = ExpenseRecord(id, amount, category, "", "", 1, ExpenseSource.MANUAL, null)
        val totals = ExpenseTotals.from(listOf(record(1, 3_000_000_000, food), record(2, 50, renamed)))
        assertEquals(3_000_000_050, totals.monthlyCents)
        assertEquals(mapOf("food" to 3_000_000_050L), totals.byCategory)
        assertEquals(0L, ExpenseTotals.from(emptyList()).monthlyCents)
    }
}
