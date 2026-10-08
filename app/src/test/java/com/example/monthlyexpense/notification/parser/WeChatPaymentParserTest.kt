package com.example.monthlyexpense.notification.parser

import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatPaymentParserTest {
    private val parser = WeChatPaymentParser()

    @Test
    fun parsesContextualAmountsAndOptionalMerchant() {
        val cases = listOf(
            Triple("微信支付", "向星巴克付款成功，金额¥25", 2_500L),
            Triple("微信支付", "付款成功 ￥25.8", 2_580L),
            Triple("微信支付", "支付成功，金额25.80元", 2_580L),
            Triple("微信支付", "扣款成功：6元", 600L)
        )

        cases.forEachIndexed { index, (title, text, expected) ->
            val result = parser.parse(raw(title = title, text = text, key = "key-$index"))
            assertEquals(expected, result?.amountCents)
            assertEquals(PaymentSource.WECHAT, result?.source)
            assertTrue((result?.confidence ?: 0f) >= 0.8f)
        }

        assertEquals("星巴克", parser.parse(raw(title = "微信支付", text = "向星巴克付款成功，金额¥25"))?.merchant)
        assertNull(parser.parse(raw(title = "微信支付", text = "支付成功，金额25.80元"))?.merchant)
    }

    @Test
    fun selectsPaymentAmountInsteadOfBalanceOrOrderNumber() {
        val result = parser.parse(
            raw(
                title = "微信支付",
                text = "订单20260823001，向便利店付款成功，付款金额¥12.30，零钱余额¥88.60"
            )
        )

        assertEquals(1_230L, result?.amountCents)
        assertEquals("便利店", result?.merchant)
    }

    @Test
    fun rejectsChatIncomeRefundAndUnreliableNotifications() {
        val rejected = listOf(
            raw(title = "张三", text = "付款成功 ¥20"),
            raw(title = "家庭群（3条消息）", text = "李四：微信支付了¥20"),
            raw(title = "微信支付", text = "二维码收款到账20元"),
            raw(title = "微信支付", text = "退款成功，￥20已退回"),
            raw(title = "微信支付", text = "零钱余额为￥88.60"),
            raw(title = "微信支付", text = "付款成功"),
            raw(packageName = PaymentPackages.ALIPAY, title = "微信支付", text = "付款成功¥20")
        )

        rejected.forEach { assertNull(parser.parse(it)) }
    }

    @Test
    fun parsesCompletedPaymentWordingAndAggregatedNotification() {
        val cases = listOf(
            "已支付¥53.10" to 5_310L,
            "已支付￥53.10" to 5_310L,
            "已支付 53.10元" to 5_310L,
            "已支付53元" to 5_300L,
            "[2条]微信支付: 已支付¥53.10" to 5_310L
        )

        cases.forEachIndexed { index, (text, expectedCents) ->
            assertEquals(
                "case $index: $text",
                expectedCents,
                parser.parse(raw(title = "微信支付", text = text, key = "completed-$index"))?.amountCents
            )
        }
    }

    @Test
    fun parsesCompletedAutomaticPaymentsAndOutgoingTransfers() {
        val cases = listOf(
            "已续费¥18.00" to 1_800L,
            "扣费成功18.00元" to 1_800L,
            "自动扣款18.00元" to 1_800L,
            "免密支付成功18.00元" to 1_800L,
            "代扣成功18.00元" to 1_800L,
            "成功转账20元" to 2_000L,
            "已转账20元给张三" to 2_000L,
            "你向张三转账20元" to 2_000L
        )

        cases.forEachIndexed { index, (text, expectedCents) ->
            assertEquals(
                "case $index: $text",
                expectedCents,
                parser.parse(raw(title = "微信支付", text = text, key = "automatic-$index"))?.amountCents
            )
        }
    }

    @Test
    fun rejectsPendingFailedAndIncomeNotificationsBeforeExpenseMatching() {
        val rejected = listOf(
            "将在8月26日自动扣费18.00元",
            "预计扣费18元",
            "将自动扣款18元",
            "扣款提醒：将在明天扣款18元",
            "即将自动续费18元",
            "自动扣费失败，余额不足",
            "支付失败，曾支付成功18元",
            "已取消，原支付金额18元",
            "已转账20元给张三，交易已撤销",
            "已支付18元，交易关闭",
            "付款未完成，金额18元",
            "收款18.00元已到账",
            "收到一笔18.00元"
        )

        rejected.forEachIndexed { index, text ->
            assertNull("case $index: $text", parser.parse(raw(title = "微信支付", text = text)))
        }
    }

    @Test
    fun rejectsServicePromotionWhenPaymentWordsAndRewardAmountAreUnrelated() {
        assertNull(
            parser.parse(raw(title = "微信支付", text = "自动扣款服务已开通，赠送18元体验金"))
        )
    }

    @Test
    fun parsesThousandsSeparatorWithoutTakingOnlyTheTrailingDigits() {
        assertEquals(
            123_456L,
            parser.parse(raw(title = "微信支付", text = "已支付1,234.56元"))?.amountCents
        )
    }

    @Test
    fun classifiesPurchaseAutomaticPaymentAndTransfer() {
        assertEquals(
            ExpenseSubtype.PURCHASE,
            parser.parse(raw(title = "微信支付", text = "已支付53.10元"))?.subtype
        )
        assertEquals(
            ExpenseSubtype.AUTOPAY,
            parser.parse(raw(title = "微信支付", text = "已续费18元"))?.subtype
        )
        assertEquals(
            ExpenseSubtype.TRANSFER,
            parser.parse(raw(title = "微信支付", text = "已转账20元给张三"))?.subtype
        )
    }

    @Test
    fun acceptsPaymentIdentityFromSubText() {
        assertEquals(
            5_310L,
            parser.parse(
                raw(title = "服务通知", text = "已支付53.10元", subText = "微信\u3000支付")
            )?.amountCents
        )
    }

    private fun raw(
        packageName: String = PaymentPackages.WECHAT,
        title: String,
        text: String,
        key: String = "wechat-key",
        subText: String = ""
    ) = RawNotification(
        packageName = packageName,
        title = title,
        text = text,
        bigText = "",
        textLines = emptyList(),
        postTime = 1_777_777_777_000,
        notificationKey = key,
        channelId = "wechat_pay",
        subText = subText
    )
}
