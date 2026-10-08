package com.example.monthlyexpense.data

import com.example.monthlyexpense.ExpenseCategory
import com.example.monthlyexpense.ExpenseRecord

internal data class ExpenseHomeDatabaseSnapshot(
    val expenses: List<ExpenseRecord>,
    val categories: List<ExpenseCategory>,
    val monthlyBudgetCents: Long,
    val todayTotalCents: Long
)

data class ExpenseHomeSnapshot(
    val expenses: List<ExpenseRecord>,
    val categories: List<ExpenseCategory>,
    val monthlyBudgetCents: Long,
    val dailyBudgetCents: Long,
    val todayTotalCents: Long,
    val autoBookkeepingEnabled: Boolean,
    val weChatEnabled: Boolean,
    val alipayEnabled: Boolean
)
