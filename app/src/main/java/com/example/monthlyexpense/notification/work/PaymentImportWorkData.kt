package com.example.monthlyexpense.notification.work

import androidx.work.Data
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.ExpenseSubtype
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.repository.PaymentEventId

object PaymentImportWorkData {
    fun create(payment: ParsedPayment): Data = Data.Builder()
        .putString(KEY_SOURCE, payment.source.name)
        .putLong(KEY_AMOUNT_CENTS, payment.amountCents)
        .putString(KEY_MERCHANT, payment.merchant.orEmpty())
        .putLong(KEY_TIME, payment.time)
        .putString(KEY_NOTIFICATION_KEY, payment.notificationKey)
        .putFloat(KEY_CONFIDENCE, payment.confidence)
        .putString(KEY_SUBTYPE, payment.subtype.name)
        .build()

    fun parse(data: Data): ParsedPayment? {
        val source = data.getString(KEY_SOURCE)
            ?.let { runCatching { PaymentSource.valueOf(it) }.getOrNull() }
            ?: return null
        val amountCents = data.getLong(KEY_AMOUNT_CENTS, -1L)
        val time = data.getLong(KEY_TIME, -1L)
        val notificationKey = data.getString(KEY_NOTIFICATION_KEY).orEmpty()
        val confidence = data.getFloat(KEY_CONFIDENCE, Float.NaN)
        val subtype = data.getString(KEY_SUBTYPE)?.let {
            runCatching { ExpenseSubtype.valueOf(it) }.getOrNull() ?: return null
        } ?: ExpenseSubtype.PURCHASE
        if (amountCents <= 0L || time <= 0L || notificationKey.isBlank() || confidence !in 0f..1f) {
            return null
        }
        return ParsedPayment(
            source = source,
            amountCents = amountCents,
            merchant = data.getString(KEY_MERCHANT)?.takeIf(String::isNotBlank),
            time = time,
            notificationKey = notificationKey,
            confidence = confidence,
            subtype = subtype
        )
    }

    fun uniqueWorkName(payment: ParsedPayment): String {
        val packageName = when (payment.source) {
            PaymentSource.WECHAT -> PaymentPackages.WECHAT
            PaymentSource.ALIPAY -> PaymentPackages.ALIPAY
        }
        val eventId = PaymentEventId.create(
            packageName = packageName,
            notificationKey = payment.notificationKey,
            postTime = payment.time,
            amountCents = payment.amountCents
        )
        return "$WORK_NAME_PREFIX${eventId.dedupeId}"
    }

    private const val WORK_NAME_PREFIX = "payment-import:"
    private const val KEY_SOURCE = "source"
    private const val KEY_AMOUNT_CENTS = "amount_cents"
    private const val KEY_MERCHANT = "merchant"
    private const val KEY_TIME = "time"
    private const val KEY_NOTIFICATION_KEY = "notification_key"
    private const val KEY_CONFIDENCE = "confidence"
    private const val KEY_SUBTYPE = "subtype"
}
