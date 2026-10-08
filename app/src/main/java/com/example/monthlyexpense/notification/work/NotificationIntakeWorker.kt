package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.notification.NotificationPipelineLog
import kotlinx.coroutines.CancellationException

class NotificationIntakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val batchId = inputData.getString("batch_id") ?: return Result.failure()
        return try {
            (applicationContext as MonthlyExpenseApplication).container.notificationIntake.drainBatch(batchId)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) {
            NotificationPipelineLog.event("intake_worker_failed type=${error.javaClass.simpleName}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
