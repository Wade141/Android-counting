package com.example.monthlyexpense

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.example.monthlyexpense.backup.ExpenseBackupManager
import com.example.monthlyexpense.budget.BudgetSettings
import com.example.monthlyexpense.data.ExpenseRepository
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.notification.AutoBookkeepingSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AppContainer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    val screenshotSessionId: String = java.util.UUID.randomUUID().toString()
    val database = ExpenseDatabaseHelper(appContext)
    val dataChanges = com.example.monthlyexpense.data.DataChangePublisher()
    val paymentRules = com.example.monthlyexpense.notification.rules.PaymentRuleStore(appContext)
    val persistenceCoordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true)) {
        dataChanges.publishHandledByHome(com.example.monthlyexpense.data.DataDomain.entries.toSet())
    }
    val budgetSettings = BudgetSettings(appContext)
    val categoryRuleRepository = com.example.monthlyexpense.classification.CategoryRuleRepository(database.categoryRuleDao, persistenceCoordinator, dataChanges, ioDispatcher)
    val autoSettings = AutoBookkeepingSettings(appContext)
    val appNotifications by lazy { com.example.monthlyexpense.alerts.AppNotifications(appContext) }
    fun notificationEvents(helper: ExpenseDatabaseHelper = database) =
        com.example.monthlyexpense.notification.repository.NotificationEventRepository(helper, dataChanges::publish).apply {
            onExpenseRecorded = { appNotifications.expenseRecorded(helper, it) }
        }
    val notifications by lazy { com.example.monthlyexpense.notification.NotificationRuntime(appContext, this) }
    val notificationIntake by lazy { com.example.monthlyexpense.notification.NotificationIntakeRuntime(appContext, this) }
    val replayOperations by lazy { com.example.monthlyexpense.notification.replay.ReplayOperationStore(appContext) }
    val expenseRepository = ExpenseRepository(
        database = database,
        budgetSettings = budgetSettings,
        autoSettings = autoSettings,
        ioDispatcher = ioDispatcher,
        initializeSourcePolicy = {
            notificationIntake.initializeWithinCoordinator()
            check(persistenceCoordinator.mutationTicket() != null) { "Ledger settings are waiting for restore reconciliation" }
        },
        onChanged = dataChanges::publishHandledByHome
    )
    val backupManager by lazy { ExpenseBackupManager(database.backupDao, budgetSettings, autoSettings,
        object : com.example.monthlyexpense.backup.BackupRestoreLifecycle {
            override fun begin(restoreId: String) = notificationIntake.begin(restoreId)
            override fun applySources(settings: com.example.monthlyexpense.backup.ExpenseBackupSettings) = notificationIntake.applySources(settings)
            override fun finish(restoreId: String, committed: Boolean) = notificationIntake.finish(restoreId, committed)
        }) }

    fun expenseViewModelFactory(): ViewModelProvider.Factory = ExpenseViewModel.factory(
        repository = expenseRepository,
        backupManager = backupManager,
        ioDispatcher = ioDispatcher,
        coordinator = persistenceCoordinator
    )
}
