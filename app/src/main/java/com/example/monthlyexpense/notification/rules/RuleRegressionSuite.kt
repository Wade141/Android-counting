package com.example.monthlyexpense.notification.rules

import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.decision.*

data class RuleSample(val id: String, val source: String, val title: String, val text: String,
    val action: DecisionAction, val amountCents: Long? = null, val bigText: String = "",
    val textLines: List<String> = emptyList(), val subText: String = "", val isGroupSummary: Boolean = false)
data class RuleCheck(val id: String, val expected: String, val actual: String, val previous: String) {
    val passed get() = expected == actual
    val changed get() = actual != previous
}

object RuleRegressionSuite {
    val baseline = listOf(
        RuleSample("paid", "WECHAT", "微信支付", "已支付20元", DecisionAction.AUTO, 2000),
        RuleSample("actual", "WECHAT", "微信支付", "支付成功，订单金额20元，实付18元，优惠2元", DecisionAction.AUTO, 1800),
        RuleSample("instruction", "WECHAT", "微信支付", "已支付20元，退款请联系商家", DecisionAction.AUTO, 2000),
        RuleSample("conflict", "WECHAT", "微信支付", "支付成功，金额18元，金额20元", DecisionAction.REVIEW),
        RuleSample("reward", "ALIPAY", "支付宝", "支付成功，领取2元红包", DecisionAction.REVIEW),
        RuleSample("balance", "ALIPAY", "支付宝", "支付成功，余额20元", DecisionAction.REVIEW),
        RuleSample("order", "ALIPAY", "支付宝", "支付成功，订单金额20元，优惠2元", DecisionAction.REVIEW),
        RuleSample("failed", "WECHAT", "微信支付", "扣款18元失败", DecisionAction.IGNORE),
        RuleSample("pending", "WECHAT", "微信支付", "明天自动扣款18元", DecisionAction.IGNORE),
        RuleSample("transfer", "WECHAT", "微信支付", "成功转账20元", DecisionAction.REVIEW, 2000),
        RuleSample("refund", "WECHAT", "微信支付", "退款成功18元", DecisionAction.IGNORE),
        RuleSample("chat", "WECHAT", "张三", "付款成功20元", DecisionAction.IGNORE),
        RuleSample("alipay", "ALIPAY", "交易提醒", "你有一笔28.50元的支出，领2元小荷包支付红包。", DecisionAction.AUTO, 2850),
        RuleSample("account", "ALIPAY", "支付成功通知", "账户100****0000于01月01日00时00分成功付款20.00元", DecisionAction.AUTO, 2000)
    )

    fun check(candidate: PaymentRules, previous: PaymentRules, extra: List<RuleSample>): List<RuleCheck> =
        (baseline + extra).map { sample ->
            val raw = RawNotification(if (sample.source == "WECHAT") PaymentPackages.WECHAT else PaymentPackages.ALIPAY,
                sample.title, sample.text, sample.bigText, sample.textLines, 1_790_000_000_000, sample.id, null,
                sample.subText, sample.isGroupSummary)
            fun signature(d: PaymentDecision) = "${d.action}:${d.amountCents ?: ""}"
            RuleCheck(sample.id, "${sample.action}:${sample.amountCents ?: ""}",
                signature(PaymentDecisionEngine(candidate).evaluate(raw)),
                signature(PaymentDecisionEngine(previous).evaluate(raw)))
        }
}
