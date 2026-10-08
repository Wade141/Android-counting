package com.example.monthlyexpense.notification.parser

import java.math.BigDecimal

internal object PaymentAmountParser {
    fun toCents(rawAmount: String): Long? {
        return try {
            val amount = BigDecimal(rawAmount.replace(",", ""))
            if (amount.scale() > 2 || amount.signum() <= 0) null
            else amount.movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    const val AMOUNT_PATTERN =
        "(?:[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)"
}
