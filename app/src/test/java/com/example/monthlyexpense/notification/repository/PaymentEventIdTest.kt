package com.example.monthlyexpense.notification.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentEventIdTest {
    @Test
    fun sameNotificationEventIsStableButLaterReuseGetsANewId() {
        val first = PaymentEventId.create(
            packageName = "com.tencent.mm",
            notificationKey = "synthetic-wechat-1",
            postTime = 1_000L,
            amountCents = 5_310L
        )
        val repeated = PaymentEventId.create(
            packageName = "com.tencent.mm",
            notificationKey = "synthetic-wechat-1",
            postTime = 1_000L,
            amountCents = 5_310L
        )
        val laterPayment = PaymentEventId.create(
            packageName = "com.tencent.mm",
            notificationKey = "synthetic-wechat-1",
            postTime = 20_000L,
            amountCents = 5_310L
        )

        assertEquals(first, repeated)
        assertEquals(first.notificationPrefix, laterPayment.notificationPrefix)
        assertNotEquals(first.dedupeId, laterPayment.dedupeId)
        assertTrue(first.dedupeId.startsWith("v2:"))
    }

    @Test
    fun amountAndRawNotificationIdentityBothAffectTheId() {
        val base = PaymentEventId.create("com.tencent.mm", "key", 1_000L, 100L)
        val anotherAmount = PaymentEventId.create("com.tencent.mm", "key", 1_000L, 200L)
        val anotherKey = PaymentEventId.create("com.tencent.mm", "other-key", 1_000L, 100L)

        assertNotEquals(base.dedupeId, anotherAmount.dedupeId)
        assertNotEquals(base.notificationPrefix, anotherKey.notificationPrefix)
    }
}
