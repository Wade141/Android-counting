package com.example.monthlyexpense.notification.decision

import com.example.monthlyexpense.notification.RawNotification
import java.security.MessageDigest

object NotificationEventIdentity {
    private fun digest(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun notification(raw: RawNotification) = digest(listOf(raw.packageName, raw.notificationKey))
    fun transaction(source: String, id: String) = digest(listOf(source, id))
    fun event(raw: RawNotification) = "v3:" + digest(listOf(
        raw.packageName, raw.notificationKey, raw.postTime.toString(), raw.isGroupSummary.toString(),
        raw.groupKey.orEmpty(), raw.title, raw.text, raw.bigText, raw.subText
    ) + raw.textLines)
}
