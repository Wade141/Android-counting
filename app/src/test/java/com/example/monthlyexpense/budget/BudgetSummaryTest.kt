package com.example.monthlyexpense.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSummaryTest {
    @Test
    fun calculatesRemainingAndOverspendingWithoutTreatingUnsetAsOverspent() {
        val normal = BudgetSummary.from(3_000, 10_000, 600, 1_000)
        assertEquals(7_000L, normal.monthlyRemainingCents)
        assertEquals(0L, normal.monthlyOverspentCents)
        assertEquals(0.7f, normal.remainingRatio, 0.0001f)
        assertEquals(0L, normal.dailyOverspentCents)

        val exact = BudgetSummary.from(10_000, 10_000, 1_000, 1_000)
        assertEquals(0L, exact.monthlyRemainingCents)
        assertEquals(0f, exact.remainingRatio, 0.0001f)

        val overspent = BudgetSummary.from(12_000, 10_000, 1_500, 1_000)
        assertEquals(-2_000L, overspent.monthlyRemainingCents)
        assertEquals(2_000L, overspent.monthlyOverspentCents)
        assertEquals(500L, overspent.dailyOverspentCents)
        assertTrue(overspent.isMonthlyOverspent)
        assertTrue(overspent.isDailyOverspent)

        val unset = BudgetSummary.from(5_000, 0, 500, 0)
        assertFalse(unset.isMonthlyBudgetSet)
        assertFalse(unset.isDailyBudgetSet)
        assertEquals(0L, unset.monthlyOverspentCents)
        assertEquals(0L, unset.dailyOverspentCents)
        assertEquals(0f, unset.remainingRatio, 0.0001f)
    }
}
