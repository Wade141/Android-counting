package com.example.monthlyexpense.budget

import android.content.Context
import com.example.monthlyexpense.MoneyLimits

class BudgetSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var dailyBudgetCents: Long
        get() = preferences.getLong(KEY_DAILY_BUDGET_CENTS, 0L)
        set(value) {
            require(value in 0..MoneyLimits.MAX_CENTS) { "Daily budget is outside the supported range" }
            preferences.edit().putLong(KEY_DAILY_BUDGET_CENTS, value).apply()
        }

    fun restoreFromBackup(value: Long): Boolean {
        require(value in 0..MoneyLimits.MAX_CENTS)
        return preferences.edit().putLong(KEY_DAILY_BUDGET_CENTS, value).commit()
    }

    companion object {
        const val PREFERENCES_NAME = "budget_settings"
        private const val KEY_DAILY_BUDGET_CENTS = "daily_budget_cents"
    }
}
