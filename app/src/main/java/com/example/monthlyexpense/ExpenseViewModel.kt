package com.example.monthlyexpense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.monthlyexpense.backup.ExpenseBackupContract
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.refresh.RefreshRequest
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class ExpenseViewModel(
    repository: ExpenseRepositoryContract,
    backupManager: ExpenseBackupContract,
    @Suppress("UNUSED_PARAMETER") savedStateHandle: SavedStateHandle,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    today: () -> LocalDate = LocalDate::now,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
    coordinator: ForegroundPersistenceCoordinator = ForegroundPersistenceCoordinator(
        ForegroundSettingsBaseline(false, true, true)
    )
) : ViewModel() {
    private val foregroundMutationAdmissionLock = Any()
    private val presentation = com.example.monthlyexpense.presentation.ExpensePresentationFactory.create(
        viewModelScope, repository, backupManager, coordinator, foregroundMutationAdmissionLock, ioDispatcher, today, zoneId
    )
    val uiState = presentation.store.state
    val events: Flow<UiEvent> = presentation.effects.events

    private val searchController = presentation.search
    fun openSearch() = searchController.open()
    fun closeSearch() = searchController.close()
    fun setSearchKeyword(value: String, isComposing: Boolean) = searchController.keyword(value, isComposing)
    fun submitSearch() = searchController.submit()
    fun openSearchFilters() = searchController.openFilters()
    fun updateSearchFilterDraft(filters: com.example.monthlyexpense.search.ExpenseSearchFilters) = searchController.draft(filters)
    fun cancelSearchFilters() = searchController.cancelFilters()
    fun resetSearchFilterDraft() = searchController.resetDraft()
    fun applySearchFilters() = searchController.applyFilters()
    fun replaceSearchFilters(filters: com.example.monthlyexpense.search.ExpenseSearchFilters) = searchController.replaceFilters(filters)
    fun clearSearch() = searchController.clear()
    fun loadMoreSearch() = searchController.more()
    fun retrySearch() = searchController.retry()
    fun openSearchExpense(id: Long) = searchController.edit(id)
    fun onSearchDataChanged(domains: Set<com.example.monthlyexpense.data.DataDomain>) = searchController.dataChanged(domains)
    fun onSearchEnvironmentChanged(today: LocalDate, zoneId: ZoneId) = searchController.environmentChanged(today, zoneId)

    fun updateNotificationState(state: NotificationListenerConnectionState) = presentation.home.updateNotificationState(state)
    fun refreshDomains(domains: Set<com.example.monthlyexpense.data.DataDomain>) = presentation.home.refreshDomains(domains)
    fun refresh(notificationState: NotificationListenerConnectionState) =
        presentation.home.request(RefreshRequest(RefreshScope.HOME, notificationState), true, true)

    fun openHistory() = presentation.history.openHistory()
    fun loadMoreHistory() = presentation.history.loadMoreHistory()
    fun retryHistoryLoad() = presentation.history.retryHistoryLoad()
    fun selectHistoryMonth(month: YearMonth?) = presentation.history.selectHistoryMonth(month)

    fun showOverlay(overlay: ExpenseOverlay) = presentation.overlays.show(overlay)

    fun dismissOverlay() {
        if (uiState.value.search.editingExpense != null) { searchController.returnFromEdit(); return }
        if (uiState.value.overlay == ExpenseOverlay.Search) { closeSearch(); return }
        synchronized(foregroundMutationAdmissionLock) {
            if (uiState.value.overlay == ExpenseOverlay.ConfirmRestore) {
                presentation.backup.dismissRestoreConfirmation()
                return
            }
            presentation.overlays.dismiss()
        }
    }

    fun prepareExport(format: BackupExportFormat, targetUri: String): Long? = presentation.backup.prepareExport(format, targetUri)
    fun documentWriteCompleted(writeId: Long, success: Boolean) = presentation.backup.documentWriteCompleted(writeId, success)
    fun acceptImportJson(json: String) = presentation.backup.acceptImportJson(json)
    fun importReadFailed() = presentation.backup.importReadFailed()
    fun confirmRestore() = presentation.backup.confirmRestore()

    fun submitNewExpense(input: NewExpenseInput) = presentation.editor.submitNewExpense(input)
    fun updateExpense(id: Long, input: ExpenseEditInput) = presentation.editor.updateExpense(id, input)
    fun deleteExpense(id: Long) = presentation.editor.deleteExpense(id)
    fun dismissCategoryCorrection() = presentation.editor.dismissCategoryCorrection()
    fun showCategoryEditor(target: CategoryEditorTarget) = presentation.category.showCategoryEditor(target)
    fun requestCategoryDelete(categoryKey: String) = presentation.category.requestCategoryDelete(categoryKey)
    fun dismissCategoryEditor() = presentation.category.dismissCategoryEditor()
    fun dismissCategoryDelete() = presentation.category.dismissCategoryDelete()
    fun addCategory(input: CategoryInput) = presentation.category.addCategory(input)
    fun updateCategory(categoryKey: String, input: CategoryInput) = presentation.category.updateCategory(categoryKey, input)
    fun deleteCategory(categoryKey: String) = presentation.category.deleteCategory(categoryKey)
    fun setBudget(kind: BudgetKind, amountCents: Long) = presentation.budget.setBudget(kind, amountCents)

    fun setAutoBookkeepingEnabled(enabled: Boolean) = presentation.settings.setAutoBookkeepingEnabled(enabled)
    fun setWeChatEnabled(enabled: Boolean) = presentation.settings.setWeChatEnabled(enabled)
    fun setAlipayEnabled(enabled: Boolean) = presentation.settings.setAlipayEnabled(enabled)

    companion object {
        fun factory(
            repository: ExpenseRepositoryContract,
            backupManager: ExpenseBackupContract,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            coordinator: ForegroundPersistenceCoordinator = ForegroundPersistenceCoordinator(
                ForegroundSettingsBaseline(false, true, true)
            )
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ExpenseViewModel(
                    repository = repository,
                    backupManager = backupManager,
                    savedStateHandle = createSavedStateHandle(),
                    ioDispatcher = ioDispatcher,
                    coordinator = coordinator
                )
            }
        }
    }
}
