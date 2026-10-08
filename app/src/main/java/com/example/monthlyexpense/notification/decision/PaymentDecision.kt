package com.example.monthlyexpense.notification.decision

import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.parser.ExpenseSubtype

enum class DecisionAction { AUTO, REVIEW, IGNORE }
enum class AmountRole { PAID, ORDER, DISCOUNT, BALANCE, REWARD, UNKNOWN }
data class AmountEvidence(val cents: Long, val role: AmountRole, val field: String, val ruleId: String)

/** Contains structured facts only. Raw notification text must never be persisted. */
data class PaymentDecision(
    val eventId: String,
    val notificationIdentity: String,
    val source: PaymentSource?,
    val observedAt: Long,
    val action: DecisionAction,
    val amountCents: Long?,
    val amounts: List<AmountEvidence>,
    val merchant: String?,
    val kind: ExpenseSubtype,
    val reasons: List<String>,
    val ruleVersion: String,
    val legacyEventId: String? = null,
    val legacyNotificationKey: String? = null,
    val transactionRef: String? = null,
    val occurredAt: Long = observedAt,
    val timeOrigin: String = "NOTIFICATION"
)

object DecisionReasons {
    fun describe(code: String): String = when (code) {
        "amount_conflict" -> "有多个可能的付款金额"
        "missing_amount" -> "未能确定实际付款金额"
        "transfer" -> "请确认这笔转账是否计入支出"
        "aggregate" -> "通知包含汇总或多笔交易，请核对"
        "unknown_status" -> "尚不能确定是否已完成付款"
        "possible_duplicate" -> "与已有记录相似，请核对是否为另一笔付款"
        "legacy_generation_unknown" -> "旧通知的接收状态和账本归属无法确认，请核对后记账"
        "success" -> "已确认付款金额"
        "unsupported_source" -> "不支持的通知来源"
        "identity_missing" -> "无法确认支付通知身份"
        "non_expense" -> "非支出或未完成交易"
        "not_transaction" -> "未发现交易信息"
        "oversized" -> "通知内容过长，请手动核对"
        else -> "请核对交易信息"
    }
}
