package com.example.monthlyexpense.notification

import com.example.monthlyexpense.notification.parser.ParsedPayment

object PaymentNotificationDebugLogger {
    @Suppress("UNUSED_PARAMETER")
    fun log(
        notification: RawNotification,
        parserName: String,
        payment: ParsedPayment?
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun logStoreResult(payment: ParsedPayment, dedupeId: String, inserted: Boolean) = Unit
}
