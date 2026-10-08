package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.work.*
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.notification.*
import kotlinx.coroutines.*
import com.example.monthlyexpense.notification.replay.*

class NotificationReplayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as MonthlyExpenseApplication).container
        val operationId = inputData.getString(OPERATION_ID) ?: id.toString()
        val store = container.replayOperations
        try {
            container.notificationIntake.initialize()
            val book = container.database.backupDao.notificationProtocol.bookGeneration()
            val operation = store.get(operationId) ?: store.create(operationId, true, book)
            if (operation.bookGeneration != book) {
                store.recoverInterrupted(book)
                return@withContext Result.success()
            }
            NotificationReplaySessionRunner(applicationContext, container).reconcileReceipts(operationId)
            if (operation.phase.terminal) return@withContext Result.success()
            val attempt = store.beginAttempt(operationId) ?: return@withContext Result.success()
            val budget = store.budget(operationId, attempt.attemptId)
            if (!container.autoSettings.isEnabled || !NotificationAccessChecker.isGranted(applicationContext) || budget == null) {
                store.finish(operationId, when {
                    !container.autoSettings.isEnabled -> ReplayTerminationReason.DISABLED
                    !NotificationAccessChecker.isGranted(applicationContext) -> ReplayTerminationReason.PERMISSION
                    else -> ReplayTerminationReason.TIMEOUT
                })
                return@withContext Result.success()
            }
            NotificationReplaySessionRunner(applicationContext, container).prepareLocal(operationId)
            store.updatePhase(operationId, ReplayPhase.CHECKING_CONNECTION)
            val outcome = container.notifications.replay(true, inputData.getBoolean(REPAIR, false), budget, operationId)
            if (store.get(operationId)?.phase?.terminal != true) store.finish(operationId, when (outcome) {
                is ReplayOutcome.Completed -> if (outcome.summary.failed == 0) null else ReplayTerminationReason.STORAGE
                is ReplayOutcome.Failed -> when (outcome.reason) {
                    ReplayFailure.BOOK_CHANGED, ReplayFailure.STALE -> ReplayTerminationReason.BOOK_CHANGED
                    ReplayFailure.PERMISSION -> ReplayTerminationReason.PERMISSION
                    ReplayFailure.DISABLED -> ReplayTerminationReason.DISABLED
                    ReplayFailure.TIMEOUT -> ReplayTerminationReason.TIMEOUT
                    ReplayFailure.STORAGE -> ReplayTerminationReason.STORAGE
                    else -> ReplayTerminationReason.READ
                }
                ReplayOutcome.Yielded -> ReplayTerminationReason.INTERRUPTED
            })
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) {
            NotificationPipelineLog.event("manual_replay_failed type=${error.javaClass.simpleName}")
            runCatching { store.get(operationId)?.takeIf { !it.phase.terminal }?.let { store.finish(operationId, ReplayTerminationReason.STORAGE) } }
            Result.failure(workDataOf(MESSAGE to "本次补记未完成，可稍后重试。", COMPLETED to false))
        }
    }

    companion object {
        const val REPAIR = "repair_connection"
        const val OPERATION_ID = "operation_id"
        const val MESSAGE = "result_message"
        const val COMPLETED = "completed"
    }
}
