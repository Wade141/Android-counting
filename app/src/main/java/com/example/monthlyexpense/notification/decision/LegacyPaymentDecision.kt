package com.example.monthlyexpense.notification.decision

import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.parser.*
import com.example.monthlyexpense.notification.repository.PaymentEventId

/** Old WorkManager payloads cannot be reparsed without their original text; preserve the old facts. */
fun legacyPaymentDecision(payment: ParsedPayment): PaymentDecision {
    val pkg = if (payment.source == PaymentSource.WECHAT) PaymentPackages.WECHAT else PaymentPackages.ALIPAY
    val oldId = PaymentEventId.create(pkg, payment.notificationKey, payment.time, payment.amountCents).dedupeId
    val raw = RawNotification(pkg, "", "", "", emptyList(), payment.time, payment.notificationKey, null)
    val transfer = payment.subtype == ExpenseSubtype.TRANSFER
    return PaymentDecision("legacy:$oldId", NotificationEventIdentity.notification(raw), payment.source, payment.time,
        if (transfer) DecisionAction.REVIEW else DecisionAction.AUTO, payment.amountCents,
        listOf(AmountEvidence(payment.amountCents, AmountRole.PAID, "legacy", "legacy.parsed")), payment.merchant,
        payment.subtype, listOf(if (transfer) "transfer" else "success"), "legacy", oldId, "$pkg:${payment.notificationKey}")
}
