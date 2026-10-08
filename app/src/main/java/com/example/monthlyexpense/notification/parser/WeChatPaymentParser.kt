package com.example.monthlyexpense.notification.parser

import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification

class WeChatPaymentParser : PaymentNotificationParser {
    override fun parse(notification: RawNotification): ParsedPayment? {
        if (notification.packageName != PaymentPackages.WECHAT) return null

        val content = notification.combinedContent()
        if (!hasPaymentIdentity(notification)) return null
        if (failedOrCancelledTerms.any(content::contains) ||
            failedOrCancelledPattern.containsMatchIn(content)
        ) return null
        if (pendingPattern.containsMatchIn(content)) return null
        if (incomeTerms.any(content::contains)) return null
        if (!spendingSignal.containsMatchIn(content)) return null

        val amountCents = contextualAmountPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(content)?.groupValues?.getOrNull(1)?.let(PaymentAmountParser::toCents)
        } ?: return null

        val merchant = merchantPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(content)?.groupValues?.getOrNull(1)?.normalizeMerchant()
        }

        return ParsedPayment(
            source = PaymentSource.WECHAT,
            amountCents = amountCents,
            merchant = merchant,
            time = notification.postTime,
            notificationKey = notification.notificationKey,
            confidence = if (merchant == null) 0.86f else 0.94f,
            subtype = content.expenseSubtype()
        )
    }

    private fun String.expenseSubtype(): ExpenseSubtype = when {
        transferSignal.containsMatchIn(this) -> ExpenseSubtype.TRANSFER
        automaticPaymentSignal.containsMatchIn(this) -> ExpenseSubtype.AUTOPAY
        else -> ExpenseSubtype.PURCHASE
    }

    private fun hasPaymentIdentity(notification: RawNotification): Boolean =
        notification.title.containsIdentity("微信支付") ||
            notification.bigText.startsWithIdentity("微信支付") ||
            notification.textLines.any { it.startsWithIdentity("微信支付") } ||
            notification.subText.containsIdentity("微信支付")

    private fun String.containsIdentity(identity: String): Boolean =
        replace(identityWhitespace, "").contains(identity)

    private fun String.startsWithIdentity(identity: String): Boolean =
        replace(identityWhitespace, "").startsWith(identity)

    private fun String.normalizeMerchant(): String? = trim()
        .replace(Regex("\\s+"), " ")
        .trim('，', ',', '。', ':', '：', ' ')
        .takeIf { it.isNotEmpty() && it.length <= 40 }

    private companion object {
        val identityWhitespace = Regex("[\\s\\u00a0\\u2007\\u202f\\u3000]+")
        val failedOrCancelledTerms = listOf(
            "支付失败", "付款失败", "扣款失败", "扣费失败", "续费失败", "交易失败",
            "余额不足", "已取消", "取消支付", "交易取消", "已撤销", "已撤回",
            "交易关闭", "未完成"
        )
        val failedOrCancelledPattern = Regex(
            "(?:付款|支付|扣款|扣费|续费|交易).{0,20}(?:失败|取消)",
            RegexOption.DOT_MATCHES_ALL
        )
        val incomeTerms = listOf(
            "收款", "到账", "退款", "退回", "收入", "红包", "转入", "二维码收款"
        )
        val pendingPattern = Regex(
            "(?:(?:预计|即将|将于|将在|将|明天|后天).{0,24}(?:扣款|扣费|续费)|" +
                "(?:扣款|扣费|续费)(?:通知|提醒))",
            RegexOption.DOT_MATCHES_ALL
        )
        val spendingSignal = Regex(
            "(?:已(?:付款|支付|扣款|续费)|" +
                "(?:付款|支付|消费|扣款|扣费|代扣|续费|免密支付|免密付款)(?:成功|完成|已成功)|" +
                "自动(?:扣款|扣费)|成功(?:付款|支付|扣款|扣费|代扣|续费|转账)|" +
                "已转账|(?:你)?向.{1,40}转账)"
        )
        val transferSignal = Regex("转账")
        val automaticPaymentSignal = Regex(
            "(?:续费|自动扣款|自动扣费|免密支付|免密付款|代扣)"
        )
        val amountCapture =
            "((?<![0-9,.])${PaymentAmountParser.AMOUNT_PATTERN}(?![0-9,.]))"
        val contextualAmountPatterns = listOf(
            Regex("(?:付款金额|支付金额|消费金额|扣款金额|扣费金额|金额)\\s*[：:]?\\s*(?:人民币|RMB)?\\s*[￥¥]?\\s*$amountCapture\\s*元?", RegexOption.IGNORE_CASE),
            Regex("(?:已(?:付款|支付|扣款|续费)|(?:付款|支付|消费|扣款|扣费|代扣|续费|免密支付|免密付款|自动扣款|自动扣费)(?:成功|完成|已成功)?|成功(?:付款|支付|扣款|扣费|代扣|续费|转账)|已转账|转账)\\s*[，,:：]?\\s*(?:金额\\s*[：:]?\\s*)?(?:人民币|RMB)?\\s*[￥¥]?\\s*$amountCapture\\s*元?", RegexOption.IGNORE_CASE)
        )
        val merchantPatterns = listOf(
            Regex("向\\s*([^\\n，,。:：]{1,40}?)\\s*(?:付款|支付)(?:成功|完成)?"),
            Regex("在\\s*([^\\n，,。:：]{1,40}?)\\s*(?:消费|付款|支付)(?:成功|完成)?"),
            Regex("商户\\s*[：:]\\s*([^\\n，,。]{1,40})")
        )
    }
}
