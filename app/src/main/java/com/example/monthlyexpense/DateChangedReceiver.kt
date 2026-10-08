package com.example.monthlyexpense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.monthlyexpense.data.DataDomain

/** The next coordinated IO refresh rechecks archiving; never access SQLite on the receiver thread. */
class DateChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_DATE_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        (context.applicationContext as MonthlyExpenseApplication).container.dataChanges
            .publish(setOf(DataDomain.LEDGER, DataDomain.SETTINGS))
    }
}
