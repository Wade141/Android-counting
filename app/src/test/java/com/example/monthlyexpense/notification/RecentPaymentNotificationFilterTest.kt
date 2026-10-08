package com.example.monthlyexpense.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPaymentNotificationFilterTest {
    private val now = 2_000_000L

    @Test
    fun acceptsSupportedNotificationInsideThirtyMinuteWindow() {
        assertTrue(
            RecentPaymentNotificationFilter.isEligible(
                packageName = PaymentPackages.ALIPAY,
                postTime = now - 29 * 60_000L - 59_000L,
                now = now
            )
        )
    }

    @Test
    fun acceptsSupportedNotificationExactlyThirtyMinutesOld() {
        assertTrue(
            RecentPaymentNotificationFilter.isEligible(
                packageName = PaymentPackages.WECHAT,
                postTime = now - 30 * 60_000L,
                now = now
            )
        )
    }

    @Test
    fun rejectsSupportedNotificationOlderThanThirtyMinutes() {
        assertFalse(
            RecentPaymentNotificationFilter.isEligible(
                packageName = PaymentPackages.ALIPAY,
                postTime = now - 30 * 60_000L - 1L,
                now = now
            )
        )
    }

    @Test
    fun rejectsNotificationDatedInTheFuture() {
        assertFalse(
            RecentPaymentNotificationFilter.isEligible(
                packageName = PaymentPackages.WECHAT,
                postTime = now + 1L,
                now = now
            )
        )
    }

    @Test
    fun rejectsUnsupportedPackageInsideWindow() {
        assertFalse(
            RecentPaymentNotificationFilter.isEligible(
                packageName = "com.example.bank",
                postTime = now,
                now = now
            )
        )
    }
}
