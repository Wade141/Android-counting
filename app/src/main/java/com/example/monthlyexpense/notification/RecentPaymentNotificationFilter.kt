package com.example.monthlyexpense.notification

object RecentPaymentNotificationFilter {
    fun isEligible(packageName: String, postTime: Long, now: Long): Boolean {
        if (!PaymentPackages.isSupported(packageName)) return false
        val age = now - postTime
        return age in 0L..REPLAY_WINDOW_MILLIS
    }

    const val REPLAY_WINDOW_MILLIS = 30 * 60_000L
}
