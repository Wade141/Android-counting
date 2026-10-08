package com.example.monthlyexpense.alerts

import android.content.Context
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.ExpenseSource
import com.example.monthlyexpense.notification.repository.LedgerNotificationProtocol
import java.util.Locale

/**
 * Reusable app notification boundary; failures never affect ledger commits.
 * Future budget callers can send AppNotice(BUDGET, "2026-10:50", title, body, Home).
 * The caller owns threshold detection; a stable key replaces the same reminder, and the
 * separate budget channel shares permission checking without coupling it to payment intake.
 * Call expenseRecorded only after the outer ledger transaction commits.
 */
class AppNotifications(context: Context, private val sink: NotificationSink = AndroidNotificationSink(context)) {
    fun send(notice: AppNotice): NoticeDelivery = try { sink.post(notice) } catch (_: RuntimeException) { NoticeDelivery.FAILED }

    fun expenseRecorded(helper: ExpenseDatabaseHelper, eventId: String): NoticeDelivery? {
        val db=helper.readableDatabase
        check(!db.inTransaction()) { "Receipt requires a committed expense" }
        val book=LedgerNotificationProtocol.currentGeneration(db)
        val id=db.rawQuery("SELECT expense_id FROM notification_events WHERE event_id = ? AND state = 'RECORDED' AND (book_generation = ? OR book_generation IS NULL)",
            arrayOf(eventId,book)).use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } ?: return null
        val expense=helper.expenseDao.findById(id) ?: return null
        val source=when(expense.source) { ExpenseSource.WECHAT_AUTO -> "微信"; ExpenseSource.ALIPAY_AUTO -> "支付宝"; else -> return null }
        val amount=String.format(Locale.ROOT,"¥%d.%02d",expense.amountCents/100,expense.amountCents%100)
        return send(AppNotice(NoticeKind.RECORDED,"$book:$id","已记账 · $amount",
            "$source · ${expense.name} · ${expense.category.name}\n支出 $amount 已保存，点击修改分类或备注。",
            NoticeDestination.EditExpense(RecordedExpenseTarget(book,id))))
    }
}
