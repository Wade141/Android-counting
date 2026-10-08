package com.example.monthlyexpense.notification

import android.util.Log
import com.example.monthlyexpense.notification.parser.ParsedPayment

/** Compact developer diagnostics; notification contents are never logged. */
object PaymentNotificationDebugLogger {
    @Suppress("UNUSED_PARAMETER")
    fun log(notification: RawNotification, parserName: String, payment: ParsedPayment?) {
        Log.d("PaymentNotification", "parser=$parserName matched=${payment != null}")
    }

    @Suppress("UNUSED_PARAMETER")
    fun logStoreResult(payment: ParsedPayment, dedupeId: String, inserted: Boolean) {
        Log.d("PaymentNotification", "inserted=$inserted")
    }
}
