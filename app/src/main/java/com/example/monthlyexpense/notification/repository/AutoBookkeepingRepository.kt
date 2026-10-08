package com.example.monthlyexpense.notification.repository

import com.example.monthlyexpense.BuiltInCategoryKeys
import com.example.monthlyexpense.ExpenseDao
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.parser.PaymentSource

class AutoBookkeepingRepository(
    private val database: ExpenseDao
) {
    fun record(payment: ParsedPayment): AutoBookkeepingResult {
        val provider = when (payment.source) {
            PaymentSource.WECHAT -> ProviderFields(
                expenseSource = ExpenseSource.WECHAT_AUTO,
                packageName = PaymentPackages.WECHAT,
                label = "微信"
            )
            PaymentSource.ALIPAY -> ProviderFields(
                expenseSource = ExpenseSource.ALIPAY_AUTO,
                packageName = PaymentPackages.ALIPAY,
                label = "支付宝"
            )
        }
        val eventId = PaymentEventId.create(
            packageName = provider.packageName,
            notificationKey = payment.notificationKey,
            postTime = payment.time,
            amountCents = payment.amountCents
        )
        val inserted = database.addAutomaticExpense(
            amountCents = payment.amountCents,
            categoryKey = BuiltInCategoryKeys.OTHER,
            name = payment.merchant ?: "${provider.label}自动记账",
            spentAt = payment.time,
            source = provider.expenseSource,
            merchant = payment.merchant,
            sourceKey = eventId.dedupeId,
            notificationPrefix = eventId.notificationPrefix,
            legacySourceKey = "${provider.packageName}:${payment.notificationKey}"
        )
        return AutoBookkeepingResult(inserted = inserted, dedupeId = eventId.dedupeId)
    }

    private data class ProviderFields(
        val expenseSource: ExpenseSource,
        val packageName: String,
        val label: String
    )
}

data class AutoBookkeepingResult(
    val inserted: Boolean,
    val dedupeId: String
)
