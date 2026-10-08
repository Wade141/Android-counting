package com.example.monthlyexpense

/** Budget totals exclude special spending, which remains part of the full ledger total. */
data class ExpenseTotals(
    val monthlyCents: Long,
    val byCategory: Map<String, Long>,
    val specialCents: Long = 0L
) {
    val allCents: Long get() = monthlyCents + specialCents
    companion object {
        fun from(expenses: List<ExpenseRecord>): ExpenseTotals {
            var total = 0L
            var special = 0L
            val categories = mutableMapOf<String, Long>()
            expenses.forEach {
                if (it.isSpecial) {
                    special += it.amountCents
                } else {
                    total += it.amountCents
                    categories[it.category.key] = (categories[it.category.key] ?: 0L) + it.amountCents
                }
            }
            return ExpenseTotals(total, categories.toMap(), special)
        }
    }
}
