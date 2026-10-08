package com.example.monthlyexpense.notification.parser

import com.example.monthlyexpense.notification.RawNotification

fun interface PaymentNotificationParser {
    fun parse(notification: RawNotification): ParsedPayment?
}
