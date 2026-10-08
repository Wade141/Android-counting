package com.example.monthlyexpense.notification.parser

import com.example.monthlyexpense.notification.PaymentPackages
import com.example.monthlyexpense.notification.RawNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayPaymentParserTest {
    private val parser = AlipayPaymentParser()

    @Test
    fun parsesAccountPaymentSuccessNotification() {
        val result = parser.parse(
            raw(
                title = "支付成功通知",
                text = "账户100****0000于01月01日00时00分成功付款20.00元"
            )
        )

        assertEquals(2_000L, result?.amountCents)
        assertEquals(PaymentSource.ALIPAY, result?.source)
        assertEquals(ExpenseSubtype.PURCHASE, result?.subtype)
        assertNull(result?.merchant)
    }

    @Test
    fun paymentSuccessTitleStillRejectsNonPaymentsAndOtherPackages() {
        val rejected = listOf(
            "付款失败，金额20.00元",
            "退款成功，20.00元已原路退回",
            "成功收款20.00元",
            "支付成功，领取2元红包",
            "账户100****0000于01月01日00时00分成功付款"
        )
        rejected.forEach { text ->
            assertNull(text, parser.parse(raw(title = "支付成功通知", text = text)))
        }
        assertNull(
            parser.parse(
                raw(
                    packageName = PaymentPackages.WECHAT,
                    title = "支付成功通知",
                    text = "账户100****0000于01月01日00时00分成功付款20.00元"
                )
            )
        )
    }

    @Test
    fun parsesContextualAmountsAndOptionalMerchant() {
        val cases = listOf(
            Triple("支付宝", "在肯德基消费成功，金额¥35", 3_500L),
            Triple("支付宝", "付款成功 ￥8.8", 880L),
            Triple("支付宝支付", "支付成功，金额2.50元", 250L),
            Triple("支付宝", "扣款成功：6元", 600L)
        )

        cases.forEachIndexed { index, (title, text, expected) ->
            val result = parser.parse(raw(title = title, text = text, key = "key-$index"))
            assertEquals(expected, result?.amountCents)
            assertEquals(PaymentSource.ALIPAY, result?.source)
            assertTrue((result?.confidence ?: 0f) >= 0.8f)
        }

        assertEquals("肯德基", parser.parse(raw(title = "支付宝", text = "在肯德基消费成功，金额¥35"))?.merchant)
        assertNull(parser.parse(raw(title = "支付宝", text = "付款成功 ￥8.8"))?.merchant)
    }

    @Test
    fun selectsPaymentAmountInsteadOfBalanceAndPoints() {
        val result = parser.parse(
            raw(
                title = "支付宝",
                text = "订单号20260823002，在便利蜂消费成功，支付金额￥18.20，余额￥120.50，积分100"
            )
        )

        assertEquals(1_820L, result?.amountCents)
        assertEquals("便利蜂", result?.merchant)
    }

    @Test
    fun parsesSyntheticTransactionReminderForPasswordlessAutomaticDebit() {
        val content = "你在示例商城平台商户有一笔18.50元的免密/自动扣款支付，点击领取2个支付宝积分。"
        val result = parser.parse(
            RawNotification(
                packageName = PaymentPackages.ALIPAY,
                title = "交易提醒",
                text = content,
                bigText = content,
                textLines = emptyList(),
                postTime = 1_700_000_000_000,
                notificationKey = "synthetic-alipay-1",
                channelId = "VPushChannel_1"
            )
        )

        assertEquals(1_850L, result?.amountCents)
        assertEquals("示例商城", result?.merchant)
        assertEquals(PaymentSource.ALIPAY, result?.source)
    }

    @Test
    fun parsesExpenseReminderEvenWhenItOffersAPaymentRedPacket() {
        val content = "你有一笔28.50元的支出，领2元小荷包支付红包。"
        val result = parser.parse(
            RawNotification(
                packageName = PaymentPackages.ALIPAY,
                title = "交易提醒",
                text = content,
                bigText = content,
                textLines = emptyList(),
                postTime = 1_700_000_000_000,
                notificationKey = "synthetic-alipay-2",
                channelId = "VPushChannel_1"
            )
        )

        assertEquals(2_850L, result?.amountCents)
        assertNull(result?.merchant)
        assertEquals(PaymentSource.ALIPAY, result?.source)
    }

    @Test
    fun rejectsPaymentSuccessWhenTheOnlyCurrencyAmountIsAPromotionalRedPacket() {
        assertNull(
            parser.parse(
                raw(title = "支付宝", text = "支付成功，领取2元红包")
            )
        )
    }

    @Test
    fun rejectsMarketingIncomeRefundAndUnreliableNotifications() {
        val rejected = listOf(
            raw(title = "支付宝", text = "消费券限时领取，最高20元优惠"),
            raw(title = "支付宝", text = "收款成功，20元已到账"),
            raw(title = "支付宝", text = "余额到账￥20"),
            raw(title = "支付宝", text = "退款成功，￥20已原路退回"),
            raw(title = "支付宝", text = "账户余额为￥120.50"),
            raw(title = "支付宝", text = "支付成功"),
            raw(title = "蚂蚁森林", text = "能量到账，快来收取"),
            raw(title = "交易提醒", text = "点击领取3个支付宝积分，最高23.90元奖励"),
            raw(packageName = PaymentPackages.WECHAT, title = "支付宝", text = "付款成功¥20")
        )

        rejected.forEach { assertNull(parser.parse(it)) }
    }

    @Test
    fun parsesCompletedPaymentsFromTransactionReminder() {
        val cases = listOf(
            "付款成功18.50元" to 1_850L,
            "支付成功18.50元" to 1_850L,
            "成功支付18.50元" to 1_850L,
            "已付款18.50元" to 1_850L,
            "消费18.50元" to 1_850L
        )

        cases.forEachIndexed { index, (text, expectedCents) ->
            assertEquals(
                "case $index: $text",
                expectedCents,
                parser.parse(raw(title = "交易提醒", text = text, key = "completed-$index"))?.amountCents
            )
        }
    }

    @Test
    fun parsesAutomaticPaymentsAndOutgoingTransfers() {
        val cases = listOf(
            "自动扣款成功15元" to 1_500L,
            "自动扣费成功15元" to 1_500L,
            "免密支付成功15元" to 1_500L,
            "免密付款成功15元" to 1_500L,
            "代扣成功15元" to 1_500L,
            "续费成功15元" to 1_500L,
            "成功转账20元" to 2_000L,
            "已转账20元给张三" to 2_000L
        )

        cases.forEachIndexed { index, (text, expectedCents) ->
            assertEquals(
                "case $index: $text",
                expectedCents,
                parser.parse(raw(title = "交易提醒", text = text, key = "automatic-$index"))?.amountCents
            )
        }
    }

    @Test
    fun rejectsPendingFailedAndIncomeTransactionReminders() {
        val rejected = listOf(
            "您的会员将在明天自动扣款15元",
            "预计扣费15元",
            "将自动扣款15元",
            "即将自动续费15元",
            "自动扣款15元失败",
            "支付失败，曾支付成功15元",
            "交易已取消，原付款金额15元",
            "已转账20元给张三，交易已撤销",
            "已付款15元，交易关闭",
            "付款未完成，金额15元",
            "成功收款88.00元",
            "88.00元已到账",
            "收到一笔88.00元"
        )

        rejected.forEachIndexed { index, text ->
            assertNull("case $index: $text", parser.parse(raw(title = "交易提醒", text = text)))
        }
    }

    @Test
    fun rejectsServicePromotionWhenPaymentWordsAndRewardAmountAreUnrelated() {
        assertNull(
            parser.parse(raw(title = "交易提醒", text = "自动扣款服务已开通，赠送18元体验金"))
        )
    }

    @Test
    fun parsesSupportedAmountBoundariesIncludingThousandsSeparator() {
        val cases = listOf(
            "付款成功¥0.01" to 1L,
            "付款成功￥1" to 100L,
            "付款成功1元" to 100L,
            "付款成功1.0元" to 100L,
            "付款成功1.00元" to 100L,
            "付款成功9999.99元" to 999_999L,
            "付款成功1,234.56元" to 123_456L
        )

        cases.forEachIndexed { index, (text, expectedCents) ->
            assertEquals(
                "case $index: $text",
                expectedCents,
                parser.parse(raw(title = "交易提醒", text = text, key = "amount-$index"))?.amountCents
            )
        }
    }

    @Test
    fun classifiesPurchaseAutomaticPaymentAndTransfer() {
        assertEquals(
            ExpenseSubtype.PURCHASE,
            parser.parse(raw(title = "交易提醒", text = "付款成功18元"))?.subtype
        )
        assertEquals(
            ExpenseSubtype.AUTOPAY,
            parser.parse(raw(title = "交易提醒", text = "自动扣款成功18元"))?.subtype
        )
        assertEquals(
            ExpenseSubtype.TRANSFER,
            parser.parse(raw(title = "交易提醒", text = "成功转账20元"))?.subtype
        )
    }

    @Test
    fun acceptsPaymentIdentityFromSubText() {
        assertEquals(
            1_850L,
            parser.parse(
                raw(title = "服务通知", text = "付款成功18.50元", subText = "支付\u3000宝")
            )?.amountCents
        )
    }

    private fun raw(
        packageName: String = PaymentPackages.ALIPAY,
        title: String,
        text: String,
        key: String = "alipay-key",
        subText: String = ""
    ) = RawNotification(
        packageName = packageName,
        title = title,
        text = text,
        bigText = "",
        textLines = emptyList(),
        postTime = 1_777_777_777_000,
        notificationKey = key,
        channelId = "alipay_pay",
        subText = subText
    )
}
