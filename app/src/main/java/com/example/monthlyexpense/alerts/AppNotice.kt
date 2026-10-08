package com.example.monthlyexpense.alerts

import android.net.Uri

/** Separate channels let users control receipt and future budget reminders independently. */
enum class NoticeKind(val channelId: String, val label: String) {
    RECORDED("recorded_expenses", "记账成功"),
    BUDGET("budget_reminders", "预算提醒")
}

data class RecordedExpenseTarget(val bookGeneration: String, val expenseId: Long) {
    fun toUri(): Uri = Uri.Builder().scheme("monthlyexpense").authority("recorded")
        .appendPath(bookGeneration).appendPath(expenseId.toString()).build()
    companion object {
        fun fromUri(uri: Uri?): RecordedExpenseTarget? {
            if (uri?.scheme != "monthlyexpense" || uri.authority != "recorded") return null
            val parts=uri.pathSegments
            if (parts.size != 2 || parts[0].isBlank() || parts[0].length > 200) return null
            val id=parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return RecordedExpenseTarget(parts[0], id)
        }
    }
}

sealed interface NoticeDestination {
    data class EditExpense(val target: RecordedExpenseTarget) : NoticeDestination
    data object Home : NoticeDestination
}

data class AppNotice(val kind: NoticeKind, val key: String, val title: String, val body: String,
    val destination: NoticeDestination)
enum class NoticeDelivery { POSTED, DISABLED, FAILED }
fun interface NotificationSink { fun post(notice: AppNotice): NoticeDelivery }
