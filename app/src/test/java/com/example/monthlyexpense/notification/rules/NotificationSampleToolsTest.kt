package com.example.monthlyexpense.notification.rules

import com.example.monthlyexpense.notification.RawNotification
import org.junit.Test
import org.junit.Assert.*

class NotificationSampleToolsTest {
    @Test fun masksNamesAccountsAndIdentifiersWithoutLosingAmountOrState() {
        val raw = RawNotification("com.tencent.mm", "微信支付", "向张三付款成功20元，账号10000000000，交易单号：A123456789", "", emptyList(), 10000, "private-key", null)
        val sanitized = NotificationSampleTools.sanitize(raw)
        assertFalse(sanitized.text.contains("张三"))
        assertFalse(sanitized.text.contains("10000000000"))
        assertFalse(sanitized.text.contains("A123456789"))
        assertTrue(sanitized.text.contains("付款成功20元"))
        assertEquals("sample", sanitized.notificationKey)
        assertNull(sanitized.channelId)
    }
}
