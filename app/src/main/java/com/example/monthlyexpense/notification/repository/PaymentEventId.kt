package com.example.monthlyexpense.notification.repository

import java.security.MessageDigest

data class PaymentEventId(
    val dedupeId: String,
    val notificationPrefix: String
) {
    companion object {
        fun create(
            packageName: String,
            notificationKey: String,
            postTime: Long,
            amountCents: Long
        ): PaymentEventId {
            val notificationIdentity = lengthPrefixed(packageName) + lengthPrefixed(notificationKey)
            val notificationPrefix = "v2:${sha256(notificationIdentity)}"
            val eventHash = sha256("$notificationPrefix|$postTime|$amountCents")
            return PaymentEventId(
                dedupeId = "$notificationPrefix:$eventHash",
                notificationPrefix = notificationPrefix
            )
        }

        private fun lengthPrefixed(value: String): String = "${value.length}:$value|"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
