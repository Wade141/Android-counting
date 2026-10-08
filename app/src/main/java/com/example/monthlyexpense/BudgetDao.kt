package com.example.monthlyexpense

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.YearMonth

class BudgetDao internal constructor(
    private val helper: ExpenseDatabaseHelper
) {
    fun getOrCreateMonthlyBudget(month: YearMonth): Long {
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            val amount = getOrCreateMonthlyBudget(database, month)
            database.setTransactionSuccessful()
            amount
        } finally {
            database.endTransaction()
        }
    }

    internal fun getOrCreateMonthlyBudget(database: SQLiteDatabase, month: YearMonth): Long {
        val monthKey = month.toString()
        val existing = queryBudget(database, monthKey)
        val amount = existing ?: database.rawQuery(
            "SELECT amount_cents FROM monthly_budgets WHERE month_key < ? ORDER BY month_key DESC LIMIT 1",
            arrayOf(monthKey)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        if (existing == null) {
            database.insertOrThrow("monthly_budgets", null, ContentValues().apply {
                put("month_key", monthKey)
                put("amount_cents", amount)
            })
        }
        return amount
    }

    fun setMonthlyBudget(month: YearMonth, amountCents: Long): Boolean {
        if (amountCents !in 0..MoneyLimits.MAX_CENTS) return false
        return helper.writableDatabase.insertWithOnConflict(
            "monthly_budgets",
            null,
            ContentValues().apply {
                put("month_key", month.toString())
                put("amount_cents", amountCents)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    private fun queryBudget(database: SQLiteDatabase, monthKey: String): Long? =
        database.query(
            "monthly_budgets",
            arrayOf("amount_cents"),
            "month_key = ?",
            arrayOf(monthKey),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
}
