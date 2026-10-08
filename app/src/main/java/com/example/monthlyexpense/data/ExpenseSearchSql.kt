package com.example.monthlyexpense.data

import com.example.monthlyexpense.search.ExpenseSearchQuery

internal data class ExpenseSearchPredicate(val selection: String, val args: List<String>)

internal fun buildExpenseSearchPredicate(query: ExpenseSearchQuery): ExpenseSearchPredicate {
    val clauses = mutableListOf<String>()
    val args = mutableListOf<String>()
    fun add(clause: String, value: Any) { clauses += clause; args += value.toString() }
    if (query.keyword.isNotEmpty()) {
        val literal = query.keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        clauses += "(e.name LIKE ? ESCAPE '\\' OR e.note LIKE ? ESCAPE '\\' OR e.merchant LIKE ? ESCAPE '\\')"
        repeat(3) { args += "%$literal%" }
    }
    query.fromInclusiveMillis?.let { add("e.spent_at >= ?", it) }
    query.untilExclusiveMillis?.let { add("e.spent_at < ?", it) }
    query.minAmountCents?.let { add("e.amount_cents >= ?", it) }
    query.maxAmountCents?.let { add("e.amount_cents <= ?", it) }
    if (query.categoryKeys.isNotEmpty()) {
        require(query.categoryKeys.size <= 100)
        clauses += "e.category IN (${query.categoryKeys.joinToString { "?" }})"
        args += query.categoryKeys.sorted()
    }
    if (query.sources.isNotEmpty()) {
        clauses += "e.source IN (${query.sources.joinToString { "?" }})"
        args += query.sources.map { it.name }.sorted()
    }
    return ExpenseSearchPredicate(clauses.joinToString(" AND ").ifEmpty { "1 = 1" }, args)
}
