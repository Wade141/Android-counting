package com.example.monthlyexpense.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.monthlyexpense.ExpenseDatabaseHelper

/** Posts a receipt for an existing record without instrumentation's process force-stop. */
class RecordedNoticeDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.monthlyexpense.DEBUG_RECORDED_NOTICE") return
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val status = ExpenseDatabaseHelper(appContext).use { helper ->
                    val eventId = helper.readableDatabase.rawQuery(
                        "SELECT n.event_id FROM notification_events n JOIN expenses e ON e.id=n.expense_id " +
                            "WHERE n.state='RECORDED' AND (n.book_generation IS NULL OR " +
                            "n.book_generation=(SELECT book_generation FROM ledger_metadata WHERE singleton=1)) " +
                            "ORDER BY e.id DESC LIMIT 1", null
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (eventId == null) "NO_EXISTING_RECORDED_EXPENSE"
                    else AppNotifications(appContext).expenseRecorded(helper, eventId)?.name ?: "NO_MATCH"
                }
                Log.i("RecordedNoticeSmoke", status)
            } catch (_: Exception) {
                Log.e("RecordedNoticeSmoke", "FAILED")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
