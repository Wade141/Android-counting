package com.example.monthlyexpense.notification.work

import android.content.Context
import com.example.monthlyexpense.ExpenseDatabaseHelper

/** Recover committed events if a process died between staging and WorkManager enqueue. */
suspend fun enqueueStagedEvents(context: Context) {
    val scheduler = PaymentImportScheduler(context)
    var cursor: com.example.monthlyexpense.notification.repository.StagedCursor? = null
    while (true) {
        val page = ExpenseDatabaseHelper(context).use {
            com.example.monthlyexpense.notification.repository.NotificationEventRepository(it).stagedPage(cursor)
        }
        if (page.isEmpty()) break
        page.forEach { scheduler.enqueueEvent(it.eventId) }
        cursor = page.last()
    }
}
