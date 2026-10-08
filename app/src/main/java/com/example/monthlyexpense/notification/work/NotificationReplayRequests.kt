package com.example.monthlyexpense.notification.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.work.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.*
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.notification.replay.*

data class ReplayUiState(val busy: Boolean = false, val resultId: String? = null, val message: String? = null,
                         val completed: Boolean = false, val operation: ReplayOperation? = null,
                         val progress: String? = null)

/** Pending work is durable; terminal results also survive WorkManager pruning until dismissed. */
class NotificationReplayRequests(context: Context) {
    private val app = context.applicationContext
    private val container = (app as MonthlyExpenseApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = WorkManager.getInstance(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val preferenceChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
    private val operations = flow {
        container.notificationIntake.initialize()
        val runner = com.example.monthlyexpense.notification.NotificationReplaySessionRunner(app, container)
        emitAll(container.replayOperations.observeLatestManual().map { operation ->
            if (operation?.phase?.terminal == true) {
                runner.reconcileReceipts(operation.operationId)
                container.replayOperations.get(operation.operationId)
            } else operation
        }.distinctUntilChanged())
    }.flowOn(Dispatchers.IO)
    val state: Flow<ReplayUiState> = combine(manager.getWorkInfosForUniqueWorkFlow(WORK_NAME), preferenceChanges,
        operations) { infos, _, operation ->
        val dismissedId = preferences.getString("dismissed", null)
        val savedId = preferences.getString("result_id", null)
        val latest = infos.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith(ORDER) }?.removePrefix(ORDER)?.toLongOrNull() ?: 0L }
        when {
            operation != null -> ReplayUiState(
                busy = !operation.phase.terminal || infos.any { !it.state.isFinished },
                resultId = operation.operationId, completed = operation.phase == ReplayPhase.COMPLETED,
                operation = operation.copy(dismissed = operation.dismissed || operation.operationId == dismissedId),
                progress = operation.progressMessage.takeIf { it.isNotEmpty() })
            latest != null && !latest.state.isFinished -> ReplayUiState(busy = true)
            savedId != null && (latest == null || latest.id.toString() == savedId) -> {
                if (savedId == dismissedId) ReplayUiState() else ReplayUiState(resultId = savedId,
                    message = preferences.getString("result_message", null),
                    completed = preferences.getBoolean("result_completed", false))
            }
            latest == null -> ReplayUiState()
            latest.id.toString() == dismissedId -> ReplayUiState()
            else -> ReplayUiState(resultId = latest.id.toString(),
                message = latest.outputData.getString(NotificationReplayWorker.MESSAGE) ?: "本次补记已中断，请重试。",
                completed = latest.outputData.getBoolean(NotificationReplayWorker.COMPLETED, false))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun request(repair: Boolean, retryOperationId: String? = null) = withContext(Dispatchers.IO) { requestMutex.withLock {
        // KEEP is the durable guard; the mutex also covers two UI requests before either is enqueued.
        val existing = manager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
        if (existing.any { !it.state.isFinished }) return@withLock
        val runtime = (app as? com.example.monthlyexpense.MonthlyExpenseApplication)?.container?.notifications
        runtime?.beginManualSubmission()
        var operationId: String? = null
        try {
        container.notificationIntake.initialize()
        val book = container.database.backupDao.notificationProtocol.bookGeneration()
        val store = container.replayOperations
        val operation = if (retryOperationId != null) {
            val previous = requireNotNull(store.get(retryOperationId))
            check(previous.bookGeneration == book && previous.canRetry)
            previous
        } else store.create(manual = true, bookGeneration = book)
        operationId = operation.operationId
        checkNotNull(store.beginAttempt(operation.operationId, userRetry = retryOperationId != null))
        val order = maxOf(System.currentTimeMillis(), preferences.getLong("order", 0L) + 1L)
        preferences.edit().putLong("order", order).apply()
        val request = OneTimeWorkRequestBuilder<NotificationReplayWorker>()
            .setInputData(workDataOf(NotificationReplayWorker.REPAIR to repair, ORDER_KEY to order,
                NotificationReplayWorker.OPERATION_ID to operation.operationId))
            .addTag("$ORDER$order").build()
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
        } catch (error: Exception) {
            operationId?.let { container.replayOperations.finish(it, ReplayTerminationReason.INTERRUPTED) }
            throw error
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runtime?.endManualSubmission() }
        }
    } }

    fun dismiss(resultId: String) {
        preferences.edit().putString("dismissed", resultId).apply()
        scope.launch {
            runCatching { if (container.replayOperations.get(resultId) != null) container.replayOperations.dismiss(resultId) }
                .onFailure { com.example.monthlyexpense.notification.NotificationPipelineLog.event("replay_dismiss_save_failed") }
        }
    }

    companion object {
        const val WORK_NAME = "manual_notification_replay"
        internal const val PREFERENCES = "notification_replay_ui"
        internal const val ORDER_KEY = "request_order"
        private const val ORDER = "replay_order_"
        private val requestMutex = Mutex()
    }
}
