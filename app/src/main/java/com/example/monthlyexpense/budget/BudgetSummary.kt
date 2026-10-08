package com.example.monthlyexpense.budget

import kotlin.math.max
import java.math.BigDecimal
import java.math.RoundingMode

data class BudgetSummary(
    val monthlySpentCents: Long,
    val monthlyBudgetCents: Long,
    val todaySpentCents: Long,
    val dailyBudgetCents: Long,
    val monthlyRemainingCents: Long,
    val monthlyOverspentCents: Long,
    val dailyOverspentCents: Long,
    val remainingRatio: Float
) {
    val isMonthlyBudgetSet: Boolean get() = monthlyBudgetCents > 0
    val isDailyBudgetSet: Boolean get() = dailyBudgetCents > 0
    val isMonthlyOverspent: Boolean get() = monthlyOverspentCents > 0
    val isDailyOverspent: Boolean get() = dailyOverspentCents > 0

    companion object {
        fun from(
            monthlySpentCents: Long,
            monthlyBudgetCents: Long,
            todaySpentCents: Long,
            dailyBudgetCents: Long
        ): BudgetSummary {
            val remaining = monthlyBudgetCents - monthlySpentCents
            val monthlyOverspent = if (monthlyBudgetCents > 0) {
                max(monthlySpentCents - monthlyBudgetCents, 0)
            } else 0
            val dailyOverspent = if (dailyBudgetCents > 0) {
                max(todaySpentCents - dailyBudgetCents, 0)
            } else 0
            val ratio = if (monthlyBudgetCents > 0) {
                BigDecimal.valueOf(remaining.coerceIn(0, monthlyBudgetCents))
                    .divide(BigDecimal.valueOf(monthlyBudgetCents), 6, RoundingMode.HALF_UP)
                    .toFloat()
            } else 0f
            return BudgetSummary(
                monthlySpentCents = monthlySpentCents,
                monthlyBudgetCents = monthlyBudgetCents,
                todaySpentCents = todaySpentCents,
                dailyBudgetCents = dailyBudgetCents,
                monthlyRemainingCents = remaining,
                monthlyOverspentCents = monthlyOverspent,
                dailyOverspentCents = dailyOverspent,
                remainingRatio = ratio
            )
        }
    }
}
