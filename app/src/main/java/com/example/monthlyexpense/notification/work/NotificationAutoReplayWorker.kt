package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.notification.*
import com.example.monthlyexpense.notification.replay.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationAutoReplayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as MonthlyExpenseApplication).container
        val runtime = container.notifications
        val sessionId = id.toString()
        val operationId = "auto:$sessionId"
        val store = container.replayOperations
        val sessionBudget = runtime.automaticSessions.open(sessionId) ?: return@withContext Result.success()
        try {
            if (runtime.manualPending) {
                runtime.requestAutomaticReplay()
                runtime.automaticSessions.finish(sessionId, false, yielded = true)
                return@withContext Result.success()
            }
            container.notificationIntake.initialize()
            val book = container.database.backupDao.notificationProtocol.bookGeneration()
            val operation = store.get(operationId) ?: store.create(operationId, false, book)
            if (operation.bookGeneration != book) {
                store.recoverInterrupted(book)
                runtime.automaticSessions.finish(sessionId, false)
                return@withContext Result.success()
            }
            NotificationReplaySessionRunner(applicationContext, container).reconcileReceipts(operationId)
            if (operation.phase.terminal) {
                runtime.automaticSessions.finish(sessionId, operation.phase == ReplayPhase.COMPLETED)
                return@withContext Result.success()
            }
            val attempt = store.beginAttempt(operationId)
            val durableBudget = attempt?.let { store.budget(operationId, it.attemptId) }
            if (durableBudget == null || !container.autoSettings.isEnabled || !NotificationAccessChecker.isGranted(applicationContext)) {
                store.finish(operationId, when {
                    !container.autoSettings.isEnabled -> ReplayTerminationReason.DISABLED
                    !NotificationAccessChecker.isGranted(applicationContext) -> ReplayTerminationReason.PERMISSION
                    else -> ReplayTerminationReason.TIMEOUT
                })
                runtime.automaticSessions.finish(sessionId, false)
                return@withContext Result.success()
            }
            val budget = combineAutomaticReplayBudgets(sessionBudget, durableBudget)
            NotificationReplaySessionRunner(applicationContext, container).prepareLocal(operationId)
            store.updatePhase(operationId, ReplayPhase.CHECKING_CONNECTION)
            val outcome = runtime.replay(manual = false, repair = false, budget, operationId)
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
            runtime.automaticSessions.finish(sessionId,
                successful = outcome is ReplayOutcome.Completed && outcome.summary.failed == 0 &&
                    store.get(operationId)?.phase == ReplayPhase.COMPLETED,
                yielded = outcome == ReplayOutcome.Yielded)
            NotificationPipelineLog.event("auto_replay_finished result=${outcome.javaClass.simpleName}")
        } catch (_: ReplayYielded) {
            runCatching { store.get(operationId)?.takeIf { !it.phase.terminal }?.let { store.finish(operationId, ReplayTerminationReason.INTERRUPTED) } }
            runtime.requestAutomaticReplay()
            runtime.automaticSessions.finish(sessionId, false, yielded = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) {
            runCatching { store.get(operationId)?.takeIf { !it.phase.terminal }?.let { store.finish(operationId, ReplayTerminationReason.STORAGE) } }
            runtime.automaticSessions.finish(sessionId, false)
            NotificationPipelineLog.event("auto_replay_failed type=${error.javaClass.simpleName}")
        }
        Result.success()
    }
    companion object { const val WORK_NAME = "notification_auto_replay" }
}

/** Partial reservations consume budget conservatively and never authorize unpersisted actions. */
internal fun combineAutomaticReplayBudgets(session: ReplayBudget, operation: ReplayBudget): ReplayBudget {
    var previous = ReplayBudgetState(maxOf(session.state.reads, operation.state.reads),
        session.state.recoveryUsed || operation.state.recoveryUsed,
        maxOf(session.state.readsAfterRecovery, operation.state.readsAfterRecovery))
    return ReplayBudget(previous) { next ->
        val recovery = next.recoveryUsed && !previous.recoveryUsed
        val saved = if (recovery) session.reserveRecovery() && operation.reserveRecovery()
            else session.reserveRead() && operation.reserveRead()
        if (saved) previous = next
        saved
    }
}
