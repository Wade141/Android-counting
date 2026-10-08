package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.monthlyexpense.notification.parser.ParsedPayment
import com.example.monthlyexpense.notification.NotificationPipelineLog

class PaymentImportScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun enqueueEvent(eventId: String) {
        val started = com.example.monthlyexpense.notification.NotificationMetrics.begin(com.example.monthlyexpense.notification.PipelineMetric.SCHEDULE)
        try {
        val request = OneTimeWorkRequestBuilder<PaymentImportWorker>()
            .setInputData(workDataOf("event_id" to eventId)).build()
        workManager.enqueueUniqueWork("payment-event:$eventId", ExistingWorkPolicy.KEEP, request).await()
        } finally { com.example.monthlyexpense.notification.NotificationMetrics.end(com.example.monthlyexpense.notification.PipelineMetric.SCHEDULE, started) }
    }

    suspend fun enqueueAndAwait(payment: ParsedPayment): String {
        val workName = PaymentImportWorkData.uniqueWorkName(payment)
        val request = OneTimeWorkRequestBuilder<PaymentImportWorker>()
            .setInputData(PaymentImportWorkData.create(payment))
            .build()
        NotificationPipelineLog.event("enqueue_requested work=${request.id}")
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request).await()
        NotificationPipelineLog.event("enqueue_completed work=${request.id}")
        return workName
    }
}
