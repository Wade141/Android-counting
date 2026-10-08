package com.example.monthlyexpense.notification.decision

import com.example.monthlyexpense.notification.RawNotification
import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.rules.PaymentRules
import org.junit.Assert.*
import org.junit.Test

class PaymentDecisionEngineTest {
    private val engine = PaymentDecisionEngine()
    private fun raw(text: String, title: String = "微信支付") = RawNotification(
        PaymentPackages.WECHAT, title, text, "", emptyList(), 1_790_000_000_000, "key", "pay"
    )

    @Test fun contextualAmountsAndExplanations() {
        listOf(
            "已支付20元，退款请联系商家" to 2000L,
            "支付成功，实付18元，订单金额20元，优惠2元" to 1800L,
            "订单金额20元，优惠2元，实付18元，支付成功" to 1800L,
            "已支付1,234.56元，余额88元" to 123456L,
            "向便利店付款成功，支付金额18.20元" to 1820L
        ).forEach { (text, cents) ->
            val decision = engine.evaluate(raw(text))
            assertEquals(text, DecisionAction.AUTO, decision.action)
            assertEquals(text, cents, decision.amountCents)
        }
    }

    @Test fun ambiguousAndTransferNeedReview() {
        listOf("支付成功，金额18元，金额20元", "成功转账20元", "支付成功，领取2元红包", "支付成功，订单金额20元，优惠2元")
            .forEach { assertEquals(it, DecisionAction.REVIEW, engine.evaluate(raw(it)).action) }
    }

    @Test fun failedPendingIncomeAndChatNeverAutoRecord() {
        listOf("明天自动扣款18元", "扣款18元失败", "已付款18元，交易已撤销", "二维码收款到账18元", "退款成功18元", "领取2元红包")
            .forEach { assertEquals(it, DecisionAction.IGNORE, engine.evaluate(raw(it)).action) }
        assertEquals(DecisionAction.IGNORE, engine.evaluate(raw("付款成功20元", "张三")).action)
        assertEquals(DecisionAction.IGNORE, engine.evaluate(raw("付款成功20元").copy(packageName = "other.app")).action)
    }

    @Test fun fieldsAreNotSplicedIntoOneTransaction() {
        val d = engine.evaluate(raw("已支付20元").copy(bigText = "已支付30元"))
        assertEquals(DecisionAction.REVIEW, d.action)
        assertEquals(DecisionAction.REVIEW, engine.evaluate(raw("已支付20元").copy(isGroupSummary = true)).action)
        assertEquals(DecisionAction.AUTO, engine.evaluate(raw("已支付20元").copy(bigText = "已支付20元")).action)
    }

    @Test fun alipaySpecialFormatsAndSyntheticAccountCase() {
        listOf(
            "你在示例商城平台商户有一笔18.50元的免密/自动扣款支付，点击领取2个支付宝积分。" to 1850L,
            "你有一笔28.50元的支出，领2元小荷包支付红包。" to 2850L,
            "账户100****0000于01月01日00时00分成功付款20.00元" to 2000L
        ).forEach { (text, cents) ->
            val r = raw(text, "支付成功通知").copy(packageName = PaymentPackages.ALIPAY)
            assertEquals(text, cents, engine.evaluate(r).amountCents)
            assertEquals(text, DecisionAction.AUTO, engine.evaluate(r).action)
        }
    }

    @Test fun eventIdentityDoesNotDependOnRuleVersionOrParsedAmount() {
        val r = raw("已支付20元")
        val first = engine.evaluate(r)
        val second = PaymentDecisionEngine(PaymentRules.builtIn.copy(version = "next")).evaluate(r)
        assertEquals(first.eventId, second.eventId)
        assertNotEquals(first.eventId, engine.evaluate(r.copy(postTime = r.postTime + 1000)).eventId)
    }

    @Test fun onlyBoundedSupportedValuesCanAutoRecord() {
        listOf("已支付0元", "已支付1.234元", "已支付-20元", "已支付999999999999999999999元")
            .forEach { assertNotEquals(it, DecisionAction.AUTO, engine.evaluate(raw(it)).action) }
    }

    @Test fun unrelatedNumbersNegatedSuccessAndSameAmountMultipleTransactionsNeverAuto() {
        listOf("付款成功，点击购买20元商品", "未支付20元", "尚未付款成功20元",
            "并未支付成功20元", "未成功付款20元", "已支付20元；已支付20元",
            "已支付20元，另一笔已支付20元", "支付成功，订单金额20元",
            "支付成功后可领取奖励，金额20元", "待支付20元", "订单待付款，金额20元",
            "支付成功，订单总金额20元，优惠2元", "支付成功，商品金额20元，优惠2元")
            .forEach { assertNotEquals(it, DecisionAction.AUTO, engine.evaluate(raw(it)).action) }
    }

    @Test fun aggregateTitlesAndLocalMerchantText() {
        listOf(2, 10, 100).forEach {
            assertEquals(DecisionAction.REVIEW, engine.evaluate(raw("已支付20元", "[${it}条]微信支付")).action)
        }
        listOf("已支付20元，积分到账2个", "向退款成功商店付款成功20元").forEach {
            assertEquals(it, DecisionAction.AUTO, engine.evaluate(raw(it)).action)
        }
    }

    @Test fun explicitCompleteTransactionDateIsUsedButIncompleteDateFallsBack() {
        val d = engine.evaluate(raw("2026-09-01 12:30:00 已支付20元"))
        assertEquals("MESSAGE", d.timeOrigin)
        assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 12, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), d.occurredAt)
        val fallback = engine.evaluate(raw("09月01日12时30分已支付20元"))
        assertEquals(fallback.observedAt, fallback.occurredAt)
        val coupon = engine.evaluate(raw("已支付20元，优惠券有效期至2026-10-01 23:59"))
        assertEquals(coupon.observedAt, coupon.occurredAt)
        val expired = engine.evaluate(raw("已支付20元，优惠券有效期至2026-01-01 23:59"))
        assertEquals(expired.observedAt, expired.occurredAt)
    }
}
