package com.example.monthlyexpense.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class RawNotificationTest {
    @Test
    fun includesSubTextAndNormalizesUnicodeWhitespaceWithoutDuplicatingParts() {
        val notification = RawNotification(
            packageName = PaymentPackages.WECHAT,
            title = "\u00a0微信\u3000支付  ",
            text = "已支付\u202f￥53.10  元",
            bigText = "已支付\u202f￥53.10  元",
            textLines = listOf("  商户：便利店 "),
            subText = " 微信支付凭证 ",
            postTime = 1_000L,
            notificationKey = "key",
            channelId = "pay"
        )

        assertEquals(
            listOf("微信 支付", "已支付 ￥53.10 元", "商户：便利店", "微信支付凭证"),
            notification.contentParts()
        )
    }
}
