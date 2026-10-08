package com.example.monthlyexpense.notification.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.monthlyexpense.ExpenseDatabaseHelper
import com.example.monthlyexpense.MonthlyExpenseApplication
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.notification.NotificationPipelineLog
import com.example.monthlyexpense.notification.PaymentNotificationListener
import com.example.monthlyexpense.notification.decision.legacyPaymentDecision
import com.example.monthlyexpense.notification.decision.DecisionAction
import com.example.monthlyexpense.notification.repository.NotificationEventRepository
import com.example.monthlyexpense.notification.repository.StoreResult
import com.example.monthlyexpense.notification.intake.SourcePolicyState
import com.example.monthlyexpense.notification.parser.PaymentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaymentImportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestedId = inputData.getString("event_id")
        val legacy = if (requestedId == null) PaymentImportWorkData.parse(inputData)?.let(::legacyPaymentDecision) else null
        val eventId = requestedId ?: legacy?.eventId ?: return@withContext Result.failure()
        try {
            val container = (applicationContext as MonthlyExpenseApplication).container
            container.notificationIntake.initialize()
            val coordinator = container.persistenceCoordinator
            val ticket = coordinator.mutationTicket() ?: return@withContext Result.success()
            val mutation = coordinator.runMutation(ticket) {
                ExpenseDatabaseHelper(applicationContext).use { database ->
                    val repository = container.notificationEvents(database)
                    if (legacy != null) {
                        val legacySource = legacy.source
                        val legacyPolicy = legacySource?.let { container.notificationIntake.policies.snapshot().sources[it] }
                        // Queued legacy payloads have no trustworthy book/source provenance. Their
                        // existing facts can be reviewed, but current policy must never grant them AUTO.
                        // Conflict-ignore preserves any existing recorded/ignored event tombstone.
                        val review = legacy.copy(action = DecisionAction.REVIEW,
                            reasons = legacy.reasons.filterNot { it == "success" } + "legacy_generation_unknown")
                        repository.stageForScheduling(review, database.backupDao.notificationProtocol.bookGeneration(), legacyPolicy?.generation)
                    }
                    val metadata = repository.eventGeneration(eventId)
                    val source = metadata?.source?.let(PaymentSource::valueOf)
                    val snapshot = container.notificationIntake.policies.snapshot()
                    val policy = source?.let { snapshot.sources[it] }
                    val book = database.backupDao.notificationProtocol.bookGeneration()
                    when {
                        metadata == null -> StoreResult.REJECTED
                        !snapshot.initialized || policy == null -> StoreResult.STAGED
                        metadata.bookGeneration == book && metadata.sourceGeneration == policy.generation &&
                            snapshot.globalEnabled && policy.selected && policy.state == SourcePolicyState.ARMING -> StoreResult.STAGED
                        else -> repository.process(eventId, book,
                            if (snapshot.globalEnabled && policy.selected && policy.state == SourcePolicyState.ON) policy.generation else -1L)
                    }
                }
            }
            val stored = (mutation as? CoordinatedMutation.Executed)?.value ?: return@withContext Result.success()
            if (stored == StoreResult.REJECTED) return@withContext Result.failure()
            NotificationPipelineLog.event("event_import_completed result=$stored")
            com.example.monthlyexpense.notification.NotificationMetrics.logSnapshot()
            Result.success(workDataOf("store_result" to stored.name, "inserted" to (stored == StoreResult.INSERTED), "dedupe_id" to eventId))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: RuntimeException) {
            NotificationPipelineLog.event("event_import_error type=${error.javaClass.simpleName}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
