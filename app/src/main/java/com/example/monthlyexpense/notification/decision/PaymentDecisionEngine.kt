package com.example.monthlyexpense.notification.decision

import com.example.monthlyexpense.MoneyLimits
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.parser.*
import com.example.monthlyexpense.notification.repository.PaymentEventId
import com.example.monthlyexpense.notification.rules.PaymentRules

/** Stateless, bounded parser. Currency candidates retain field boundaries; conflicts never auto-post. */
class PaymentDecisionEngine(private val rules: PaymentRules = PaymentRules.builtIn) {
    fun evaluate(raw: RawNotification): PaymentDecision {
        val source = when (raw.packageName) {
            PaymentPackages.WECHAT -> PaymentSource.WECHAT
            PaymentPackages.ALIPAY -> PaymentSource.ALIPAY
            else -> null
        }
        val eventId = NotificationEventIdentity.event(raw)
        var kind = ExpenseSubtype.PURCHASE
        var merchant: String? = null
        var transactionRef: String? = null
        var occurredAt = raw.postTime
        var timeOrigin = "NOTIFICATION"
        val amounts = mutableListOf<AmountEvidence>()
        fun result(action: DecisionAction, reason: String, cents: Long? = null) = PaymentDecision(
            eventId, NotificationEventIdentity.notification(raw), source, raw.postTime, action,
            cents, amounts.distinct(), merchant, kind, listOf(reason), rules.version,
            cents?.let { PaymentEventId.create(raw.packageName, raw.notificationKey, raw.postTime, it).dedupeId },
            "${raw.packageName}:${raw.notificationKey}", transactionRef, occurredAt, timeOrigin
        )
        if (source == null) return result(DecisionAction.IGNORE, "unsupported_source")
        if (raw.contentParts().sumOf { it.length } > 16_000 || raw.textLines.size > 64)
            return result(DecisionAction.REVIEW, "oversized")
        val title = normalize(raw.title)
        val identities = if (source == PaymentSource.WECHAT) rules.weChatTitles else rules.alipayTitles
        val identity = identities.any { identity ->
            title == identity || title.matches(Regex("\\[\\d+条]${Regex.escape(identity)}")) ||
                normalize(raw.subText) == identity ||
                (listOf(raw.bigText) + raw.textLines).any { normalize(it).startsWith("$identity:") || normalize(it).startsWith("$identity：") }
        }
        if (!identity) return result(DecisionAction.IGNORE, "identity_missing")
        val fields = (listOf("text" to raw.text, "bigText" to raw.bigText) +
            raw.textLines.mapIndexed { index, line -> "line$index" to line })
            .map { (field, text) -> field to text.trim().replace(Regex("[\\s\\u00a0\\u2007\\u202f\\u3000]+"), " ") }
            .filter { it.second.isNotBlank() }.distinctBy { it.second }
        val content = fields.joinToString("\n") { it.second }
        val explicitTimes = Regex("(?<!\\d)(20\\d{2})[-年](\\d{1,2})[-月](\\d{1,2})(?:日|T| )\\s*(\\d{1,2})[:时](\\d{2})(?:[:分](\\d{2}))?").findAll(content).mapNotNull { match ->
            val before = content.substring(maxOf(0, match.range.first - 12), match.range.first)
            val after = content.substring(match.range.last + 1).trimStart()
            val associated = Regex("(?:交易时间|付款时间|支付时间|扣款时间)[：:]?\\s*$").containsMatchIn(before) || rules.successPhrases.any(after::startsWith)
            if (!associated) return@mapNotNull null
            runCatching {
                val g = match.groupValues
                java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()?.takeIf { it > 0 && it <= raw.postTime + 300000L }
        }.distinct().toList()
        explicitTimes.singleOrNull()?.let { occurredAt = it; timeOrigin = "MESSAGE" }
        val references = Regex("(?:交易单号|交易号|支付单号)[：:]\\s*([A-Za-z0-9]{8,64})(?![A-Za-z0-9])")
            .findAll(content).map { it.groupValues[1] }.distinct().toList()
        if (references.size == 1) transactionRef = NotificationEventIdentity.transaction(raw.packageName, references.single())
        if (references.size > 1) return result(DecisionAction.REVIEW, "aggregate")
        if (transfer.containsMatchIn(content)) kind = ExpenseSubtype.TRANSFER
        else if (autopay.containsMatchIn(content)) kind = ExpenseSubtype.AUTOPAY
        // Ignore trailing instructions, never strip actual refund/failure statements.
        val merchantMatch = Regex("(?:向|在)\\s*([^\\n，,。:：]{1,40}?)(?:平台商户)?\\s*(?:付款|支付|消费|有一笔)").find(content)
            ?: Regex("商户[：:]\\s*([^，,。\\n]{1,40})").find(content)
        merchant = merchantMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }
        val factual = content.replace(instruction, "")
            .let { text -> merchant?.let { text.replace(it, "商户") } ?: text }
            .replace(Regex("(?:积分|能量)[^，,。\\n]{0,12}到账[^，,。\\n]*"), "")
        if (failed.containsMatchIn(factual) || pending.containsMatchIn(factual) || income.containsMatchIn(factual))
            return result(DecisionAction.IGNORE, "non_expense")
        if (conditional.containsMatchIn(factual)) return result(DecisionAction.REVIEW, "unknown_status")
        val special = specialExpense.containsMatchIn(content) || specialAutopay.containsMatchIn(content)
        val success = rules.successPhrases.any(content::contains) || special ||
            Regex("(?:你)?向[^，,。\\n]{1,40}转账[￥¥]?\\s*\\d").containsMatchIn(content) ||
            (source == PaymentSource.ALIPAY && Regex("消费\\s*[￥¥]?\\d").containsMatchIn(content))
        val transaction = success || Regex("支付|付款|扣款|扣费|支出|转账|消费").containsMatchIn(content)
        if (!transaction) return result(DecisionAction.IGNORE, "not_transaction")
        fields.forEach { (field, text) ->
            money.findAll(text).forEach { match ->
                val value = PaymentAmountParser.toCents(match.groupValues[1]) ?: return@forEach
                if (value !in 1..MoneyLimits.MAX_CENTS) return@forEach
                val start = match.range.first
                val end = match.range.last + 1
                val before = text.substring(maxOf(0, start - 32), start)
                val after = text.substring(end, minOf(text.length, end + 24))
                val local = before.substringAfterLast('，').substringAfterLast(',').substringAfterLast('。')
                val role = when {
                    Regex("(?:余额|剩余)[^，,。]{0,8}$").containsMatchIn(local) -> AmountRole.BALANCE
                    Regex("(?:优惠|抵扣|减免)[^，,。]{0,8}$").containsMatchIn(local) -> AmountRole.DISCOUNT
                    Regex("(?:领取|领|奖励|赠送|红包)[^，,。]{0,8}$").containsMatchIn(local) ||
                        Regex("^\\s*元?[^，,。]{0,8}(?:红包|奖励|优惠券|体验金)").containsMatchIn(after) -> AmountRole.REWARD
                    Regex("(?:订单金额|原价|原金额)[^，,。]{0,8}$").containsMatchIn(local) -> AmountRole.ORDER
                    rules.paidLabels.any { Regex("${Regex.escape(it)}\\s*[：:]?\\s*(?:人民币|RMB)?\\s*[￥¥]?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(local) } -> AmountRole.PAID
                    special && (Regex("有一笔\\s*$").containsMatchIn(local) && Regex("^\\s*元的(?:支出|免密|自动扣款)").containsMatchIn(after)) -> AmountRole.PAID
                    Regex("(?:${rules.successPhrases.joinToString("|") { Regex.escape(it) }}|转账|消费)\\s*[，,:：]?\\s*(?:金额[：:]?)?\\s*(?:人民币|RMB)?\\s*[￥¥]?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(local) -> AmountRole.PAID
                    Regex("(?:金额)\\s*[：:]?\\s*[￥¥]?\\s*$").containsMatchIn(local) -> AmountRole.UNKNOWN
                    else -> AmountRole.UNKNOWN
                }
                // Require currency syntax or a payment/amount label; dates and account numbers are not money.
                if (!after.startsWith("元") && !Regex("[￥¥]\\s*$").containsMatchIn(before) && role == AmountRole.UNKNOWN && !local.contains("金额")) return@forEach
                val ruleId = if (role == AmountRole.UNKNOWN && Regex("^\\s*金额\\s*[：:]?\\s*[￥¥]?\\s*$").containsMatchIn(local))
                    "amount.generic_label" else "amount.${role.name.lowercase()}"
                amounts += AmountEvidence(value, role, field, ruleId)
            }
        }
        val paid = amounts.filter { it.role == AmountRole.PAID }.map { it.cents }.distinct()
        val unknown = amounts.filter { it.ruleId == "amount.generic_label" }.map { it.cents }.distinct()
        val candidates = if (paid.isNotEmpty()) paid else unknown
        val repeatedPaid = amounts.filter { it.role == AmountRole.PAID }.groupBy { it.field }.any { (_, values) -> values.size > 1 }
        val aggregatedCount = Regex("\\[(\\d+)条]").findAll(raw.title + "\n" + content).any { (it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) > 1 }
        if (raw.isGroupSummary || aggregatedCount || raw.textLines.distinct().size > 1 || repeatedPaid)
            return result(DecisionAction.REVIEW, "aggregate", candidates.singleOrNull())
        if (candidates.size > 1 || (paid.isNotEmpty() && unknown.any { it !in paid }))
            return result(DecisionAction.REVIEW, "amount_conflict")
        val cents = candidates.singleOrNull()
        if (!success) return result(DecisionAction.REVIEW, "unknown_status", cents)
        if (kind == ExpenseSubtype.TRANSFER) return result(DecisionAction.REVIEW, "transfer", cents)
        if (cents == null) return result(DecisionAction.REVIEW, "missing_amount")
        return result(DecisionAction.AUTO, "success", cents)
    }

    private fun normalize(value: String) = value.replace(Regex("[\\s\\u00a0\\u2007\\u202f\\u3000]+"), "")
    private companion object {
        val money = Regex("(?<![\\d.,-])(${PaymentAmountParser.AMOUNT_PATTERN})(?![\\d.,])")
        val transfer = Regex("转账")
        val autopay = Regex("续费|自动扣款|自动扣费|免密支付|免密付款|代扣|免密/自动")
        val instruction = Regex("退款(?:请|可)[^，,。\\n]*")
        val failed = Regex("(?:付款|支付|扣款|扣费|续费|交易)[^，,。\\n]{0,20}(?:失败|取消|撤销|关闭|未完成|未成功)|(?:尚未|并未|未)(?:成功)?(?:付款|支付|扣款|扣费)|余额不足|已取消|已撤销|已撤回|取消支付")
        val pending = Regex("(?:预计|即将|将于|将在|将|明天|后天)[^，,。\\n]{0,24}(?:扣款|扣费|续费)|(?:扣款|扣费|续费)(?:通知|提醒)")
        val conditional = Regex("(?:付款|支付|消费|扣款|扣费|续费)(?:成功|完成)(?:后|时)|(?:如果|若|当)[^，,。]{0,16}(?:支付|付款)|待(?:支付|付款|扣款)")
        val income = Regex("收款|退款成功|已退回|退回成功|转入|收入|到账")
        val specialExpense = Regex("你有一笔\\s*${PaymentAmountParser.AMOUNT_PATTERN}\\s*元的支出")
        val specialAutopay = Regex("你在[^，,。\\n]{1,40}有一笔\\s*${PaymentAmountParser.AMOUNT_PATTERN}\\s*元的(?:免密/自动扣款支付|免密支付|自动扣款支付)")
    }
}
