package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.backup.BackupController
import com.example.monthlyexpense.backup.ExpenseBackupContract
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.entry.ExpenseEditorController
import com.example.monthlyexpense.categories.CategoryManagementController
import com.example.monthlyexpense.budget.BudgetController
import com.example.monthlyexpense.history.ExpenseHistoryController
import com.example.monthlyexpense.notification.AutoBookkeepingSettingsController
import com.example.monthlyexpense.presentation.bindings.*
import com.example.monthlyexpense.presentation.data.*
import com.example.monthlyexpense.search.ExpenseSearchController
import com.example.monthlyexpense.refresh.HomeRefreshController
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** Composition only. Construction never starts IO or subscriptions. */
internal object ExpensePresentationFactory {
    fun create(scope: CoroutineScope, repository: ExpenseRepositoryContract, backupManager: ExpenseBackupContract,
        coordinator: ForegroundPersistenceCoordinator, admissionLock: Any, ioDispatcher: CoroutineDispatcher,
        today: () -> LocalDate, zoneId: () -> ZoneId): ExpensePresentation {
        val store = ForegroundUiStore()
        val effects = ForegroundEffects()
        val settings = AutoBookkeepingSettingsController(scope, store.settings,
            AutoSettingsRepositoryAdapter(repository), coordinator, effects)
        val history = ExpenseHistoryController(scope, HistoryRepositoryAdapter(repository), store.history,
            HistoryUiAdapter(store), coordinator, admissionLock, ioDispatcher)
        val home = HomeRefreshController(scope, HomeRepositoryAdapter(repository), history, settings,
            HomeDisplayAdapter(store), HomeSnapshotAdapter(store), coordinator, effects, ioDispatcher, today, zoneId)
        val searchUi = SearchUiAdapter(store)
        val search = ExpenseSearchController(scope, SearchRepositoryAdapter(repository), coordinator,
            store.search, searchUi::isVisible, searchUi, searchUi, today, zoneId)
        val mutationRunner = ForegroundMutationRunner(scope, coordinator, admissionLock, home, effects)
        val editorUi = EditorUiAdapter(store)
        val editor = ExpenseEditorController(store.editor, ExpenseRepositoryAdapter(repository), editorUi,
            store.categories, store.displayedEpoch, editorUi, editorUi, editorUi, editorUi,
            mutationRunner, coordinator, admissionLock)
        val category = CategoryManagementController(store.category, CategoryRepositoryAdapter(repository),
            store.categories, store.displayedEpoch, store.historyLoaded, mutationRunner, coordinator, admissionLock)
        val budget = BudgetController(store.budget, BudgetRepositoryAdapter(repository), store.displayedEpoch,
            BudgetUiAdapter(store), mutationRunner, coordinator, admissionLock, today)
        val restoreUi = RestoreUiAdapter(store,
            listOf(history::invalidateForRestore, editor::invalidateForRestore, category::invalidateForRestore,
                budget::invalidateForRestore, settings::invalidateForRestore),
            search::invalidateForRestore, home::invalidateForRestore, history::restoredState, settings::restoredState)
        val backup = BackupController(scope, backupManager, store.backup, BackupUiAdapter(store), effects,
            coordinator, restoreUi, restoreUi, home, admissionLock, ioDispatcher, zoneId)
        return ExpensePresentation(store, effects, settings, history, home, search, editor, category, budget, backup, OverlayUiAdapter(store))
    }
}

/** Only host receives assembly; feature controllers receive individual capabilities. */
internal class ExpensePresentation(
    val store: ForegroundUiStore,
    val effects: ForegroundEffects,
    val settings: AutoBookkeepingSettingsController,
    val history: ExpenseHistoryController,
    val home: HomeRefreshController,
    val search: ExpenseSearchController,
    val editor: ExpenseEditorController,
    val category: CategoryManagementController,
    val budget: BudgetController,
    val backup: BackupController,
    val overlays: OverlayUiAdapter,
)
