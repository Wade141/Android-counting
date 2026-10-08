package com.example.monthlyexpense.notification.parser

enum class PaymentSource {
    WECHAT,
    ALIPAY
}

enum class ExpenseSubtype {
    PURCHASE,
    AUTOPAY,
    TRANSFER
}

data class ParsedPayment(
    val source: PaymentSource,
    val amountCents: Long,
    val merchant: String?,
    val time: Long,
    val notificationKey: String,
    val confidence: Float,
    val subtype: ExpenseSubtype = ExpenseSubtype.PURCHASE
)
