package com.example.monthlyexpense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.monthlyexpense.backup.ExpenseBackupContract
import com.example.monthlyexpense.categories.CategoryDeleteResult
import com.example.monthlyexpense.categories.CategoryMutationResult
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ForegroundMutationTicket
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundRestoreTicket
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.data.RestoreCommit
import com.example.monthlyexpense.data.RestoreOutcome
import com.example.monthlyexpense.data.HISTORY_PAGE_SIZE
import com.example.monthlyexpense.data.HistoryCursor
import com.example.monthlyexpense.data.HistoryPage
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.refresh.QueuedRefresh
import com.example.monthlyexpense.refresh.RefreshRequest
import com.example.monthlyexpense.refresh.RefreshRequestMerger
import com.example.monthlyexpense.refresh.RefreshResult
import com.example.monthlyexpense.refresh.RefreshScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExpenseViewModel(
    private val repository: ExpenseRepositoryContract,
    private val backupManager: ExpenseBackupContract,
    private val savedStateHandle: SavedStateHandle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val coordinator: ForegroundPersistenceCoordinator = ForegroundPersistenceCoordinator(
        ForegroundSettingsBaseline(false, true, true)
    )
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ExpenseUiState())
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    private val refreshMerger = RefreshRequestMerger()
    private var autoBookkeepingMutationGeneration = 0L
    private var weChatMutationGeneration = 0L
    private var alipayMutationGeneration = 0L
    private var entryMutationGeneration = 0L
    private var expenseEditMutationGeneration = 0L
    private var categoryMutationGeneration = 0L
    private var budgetMutationGeneration = 0L
    private var exportMutationGeneration = 0L
    private var importValidationGeneration = 0L
    private var restoreGeneration = 0L
    private var historyLoadGeneration = 0L
    private var lastAppliedMonth: YearMonth? = null
    private var lastHomeSnapshot: ExpenseHomeSnapshot? = null
    private var lastAppliedDate: LocalDate? = null
    private var lastAppliedZone: ZoneId? = null
    private var failedRefreshDomains = emptySet<com.example.monthlyexpense.data.DataDomain>()
    private var pendingImportJson: String? = null
    private var pendingDocumentWrite: PendingDocumentWrite? = null
    private val foregroundMutationAdmissionLock = Any()

    val uiState = mutableUiState.asStateFlow()
    val events: Flow<UiEvent> = eventChannel.receiveAsFlow()

    private val searchController = com.example.monthlyexpense.search.ExpenseSearchController(
        viewModelScope, repository, coordinator, { mutableUiState.value },
        { transform -> mutableUiState.update(transform) }, today, zoneId
    )
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

    fun updateNotificationState(state: NotificationListenerConnectionState) {
        mutableUiState.update { it.copy(notificationState = state) }
    }

    fun refreshDomains(domains: Set<com.example.monthlyexpense.data.DataDomain>) {
        val relevant = (domains + failedRefreshDomains) - com.example.monthlyexpense.data.DataDomain.REVIEW
        if (relevant.isEmpty() || coordinator.readTicket() == null) return
        enqueueRefresh(QueuedRefresh(
            RefreshRequest(if (com.example.monthlyexpense.data.DataDomain.LEDGER in relevant) RefreshScope.ALL else RefreshScope.HOME,
                uiState.value.notificationState, relevant),
            reportPageFailure = true, notificationStateIsExternal = false))
    }

    fun refresh(notificationState: NotificationListenerConnectionState) {
        if (coordinator.readTicket() == null) return
        enqueueRefresh(
            QueuedRefresh(
                request = RefreshRequest(
                    scope = RefreshScope.HOME,
                    notificationState = notificationState
                ),
                reportPageFailure = true,
                notificationStateIsExternal = true
            )
        )
    }

    fun showOverlay(overlay: ExpenseOverlay) {
        mutableUiState.value = mutableUiState.value.copy(overlay = overlay)
    }

    fun openHistory() {
        if (mutableUiState.value.history.selectedMonth != null) {
            selectHistoryMonth(null)
        }
        var admission: HistoryLoadAdmission? = null
        synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            var history = state.history
            if (!history.initialLoadComplete && !history.loading && !history.loadingMore) {
                coordinator.mutationTicket()?.let { ticket ->
                    history = history.copy(
                        loading = true,
                        loadingMore = false,
                        loadFailed = false,
                        failedCursor = null
                    )
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = null
                    )
                }
            }
            mutableUiState.value = state.copy(
                overlay = ExpenseOverlay.History,
                history = history
            )
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun loadMoreHistory() {
        var admission: HistoryLoadAdmission? = null
        synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            val history = state.history
            val cursor = history.nextCursor
            if (
                history.initialLoadComplete &&
                history.hasMore &&
                cursor != null &&
                !history.loading &&
                !history.loadingMore &&
                !history.loadFailed
            ) {
                coordinator.mutationTicket()?.let { ticket ->
                    mutableUiState.value = state.copy(
                        history = history.copy(
                            loadingMore = true,
                            loadFailed = false,
                            failedCursor = null
                        )
                    )
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = cursor,
                        month = history.selectedMonth
                    )
                }
            }
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun retryHistoryLoad() {
        var admission: HistoryLoadAdmission? = null
        synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            val history = state.history
            if (history.loadFailed && !history.loading && !history.loadingMore) {
                coordinator.mutationTicket()?.let { ticket ->
                    val cursor = history.failedCursor
                    mutableUiState.value = state.copy(
                        history = history.copy(
                            loading = cursor == null,
                            loadingMore = cursor != null,
                            loadFailed = false
                        )
                    )
                    admission = HistoryLoadAdmission(
                        ticket = ticket,
                        generation = historyLoadGeneration,
                        cursor = cursor,
                        month = history.selectedMonth
                    )
                }
            }
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun selectHistoryMonth(month: YearMonth?) {
        var admission: HistoryLoadAdmission? = null
        synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            val ticket = coordinator.mutationTicket() ?: return
            val generation = ++historyLoadGeneration
            mutableUiState.value = state.copy(history = HistoryUiState(
                months = state.history.months,
                selectedMonth = month,
                loading = true
            ))
            admission = HistoryLoadAdmission(ticket, generation, null, month)
        }
        admission?.let(::launchHistoryPageLoad)
    }

    fun dismissOverlay() {
        if (uiState.value.search.editingExpense != null) { searchController.returnFromEdit(); return }
        if (uiState.value.overlay == ExpenseOverlay.Search) { closeSearch(); return }
        synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            if (state.overlay == ExpenseOverlay.ConfirmRestore) {
                pendingImportJson = null
                importValidationGeneration++
            }
            val edit = state.overlay as? ExpenseOverlay.EditExpense
            mutableUiState.value = state.copy(overlay =
                if (edit?.returnToSpecialBudget == true) ExpenseOverlay.SpecialBudgetDetails else null)
        }
    }

    fun prepareExport(format: BackupExportFormat, targetUri: String): Long? {
        val ticket = coordinator.mutationTicket() ?: return null
        val generation = synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            if (state.pending.exportPreparing) return null
            val admittedGeneration = ++exportMutationGeneration
            pendingDocumentWrite = null
            mutableUiState.value = state.copy(
                pending = state.pending.copy(exportPreparing = true)
            )
            admittedGeneration
        }
        viewModelScope.launch {
            var awaitingDocumentWrite = false
            try {
                when (val read = coordinator.runRead(ticket) {
                    withContext(ioDispatcher) {
                        when (format) {
                            BackupExportFormat.JSON -> backupManager.exportJson()
                            BackupExportFormat.CSV -> backupManager.exportCsv(zoneId())
                        }
                    }
                }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> {
                        coordinator.commitIfCurrent(ticket) {
                            if (exportMutationGeneration == generation) {
                                val write = PendingDocumentWrite(format, ticket, generation)
                                if (
                                    eventChannel.trySend(
                                        UiEvent.WriteDocument(
                                            writeId = generation,
                                            targetUri = targetUri,
                                            content = read.value
                                        )
                                    ).isSuccess
                                ) {
                                    pendingDocumentWrite = write
                                    awaitingDocumentWrite = true
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitMessageIfCurrent(
                    ticket = ticket,
                    isCurrentGeneration = { exportMutationGeneration == generation },
                    message = exportCompletionMessage(format, success = false)
                )
            } finally {
                if (!awaitingDocumentWrite) {
                    clearExportPendingIfCurrent(generation)
                }
            }
        }
        return generation
    }

    fun documentWriteCompleted(writeId: Long, success: Boolean) {
        val pendingWrite = pendingDocumentWrite
            ?.takeIf { it.generation == writeId }
            ?: return
        coordinator.commitIfCurrent(pendingWrite.ticket) {
            if (
                exportMutationGeneration == pendingWrite.generation &&
                pendingDocumentWrite == pendingWrite
            ) {
                pendingDocumentWrite = null
                mutableUiState.update { state ->
                    state.copy(
                        pending = state.pending.copy(exportPreparing = false)
                    )
                }
                emitMessage(exportCompletionMessage(pendingWrite.format, success))
            }
        }
    }

    fun acceptImportJson(json: String) {
        val ticket = coordinator.mutationTicket() ?: return
        val generation = synchronized(foregroundMutationAdmissionLock) {
            ++importValidationGeneration
        }
        viewModelScope.launch {
            try {
                when (val read = coordinator.runRead(ticket) {
                    withContext(ioDispatcher) { backupManager.validateJson(json) }
                }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> coordinator.commitIfCurrent(ticket) {
                        if (importValidationGeneration == generation) {
                            if (read.value) {
                                pendingImportJson = json
                                mutableUiState.update { state ->
                                    state.copy(overlay = ExpenseOverlay.ConfirmRestore)
                                }
                            } else {
                                clearImportConfirmation()
                                emitMessage(
                                    UiMessage("文件不是有效的 expense_report JSON 备份")
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.commitIfCurrent(ticket) {
                    if (importValidationGeneration == generation) {
                        clearImportConfirmation()
                        emitMessage(
                            UiMessage("文件不是有效的 expense_report JSON 备份")
                        )
                    }
                }
            }
        }
    }

    fun importReadFailed() {
        val ticket = coordinator.mutationTicket() ?: return
        coordinator.commitIfCurrent(ticket) {
            importValidationGeneration++
            clearImportConfirmation()
            emitMessage(UiMessage("无法读取所选文件"))
        }
    }

    fun confirmRestore() {
        val admission = synchronized(foregroundMutationAdmissionLock) {
            val json = pendingImportJson ?: return
            val ticket = coordinator.beginRestore() ?: return
            mutableUiState.update { it.copy(categoryCorrection = null) }
            searchController.resetForRestore()
            refreshMerger.invalidatePending()?.waiters?.forEach { waiter ->
                waiter.complete(RefreshResult.STALE)
            }
            val generation = ++restoreGeneration
            historyLoadGeneration++
            entryMutationGeneration++
            expenseEditMutationGeneration++
            categoryMutationGeneration++
            budgetMutationGeneration++
            autoBookkeepingMutationGeneration++
            weChatMutationGeneration++
            alipayMutationGeneration++
            exportMutationGeneration++
            importValidationGeneration++
            pendingDocumentWrite = null
            val baseline = coordinator.persistedSettings
            mutableUiState.update { state ->
                state.copy(
                    autoBookkeeping = state.autoBookkeeping.copy(
                        enabled = baseline.autoBookkeepingEnabled,
                        weChatEnabled = baseline.weChatEnabled,
                        alipayEnabled = baseline.alipayEnabled,
                        enabledWritePending = false,
                        weChatWritePending = false,
                        alipayWritePending = false
                    ),
                    pending = ExpensePendingState(restoring = true),
                    overlay = null,
                    categoryManagement = CategoryManagementUiState(),
                    history = state.history.copy(
                        loading = false,
                        loadingMore = false
                    )
                )
            }
            RestoreAdmission(ticket, generation, json)
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = withContext(NonCancellable) {
                    coordinator.runRestore(
                        ticket = admission.ticket,
                        restore = {
                            val restoreResult = withContext(ioDispatcher) {
                                backupManager.restoreDetailed(admission.json)
                            }
                            when (restoreResult) {
                                com.example.monthlyexpense.backup.BackupRestoreResult.NotCommitted -> RestoreCommit.NotRestored
                                com.example.monthlyexpense.backup.BackupRestoreResult.SettingsPending -> RestoreCommit.SettingsPending
                                is com.example.monthlyexpense.backup.BackupRestoreResult.Completed -> {
                                val settings = restoreResult.settings
                                RestoreCommit.Restored(
                                    ForegroundSettingsBaseline(
                                        autoBookkeepingEnabled =
                                            settings.autoBookkeepingEnabled,
                                        weChatEnabled = settings.weChatEnabled,
                                        alipayEnabled = settings.alipayEnabled
                                    )
                                )
                                }
                            }
                        },
                        refreshAfterRestore = { loadAndApplyRestoreSnapshot() }
                    )
                }
                currentCoroutineContext().ensureActive()
                if (restoreGeneration == admission.generation) {
                    applyPersistedSettings()
                    emitMessage(UiMessage(restoreOutcomeMessage(outcome)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (restoreGeneration == admission.generation) {
                    emitMessage(UiMessage("恢复未完成，请检查当前账本后重试。"))
                }
            } finally {
                if (restoreGeneration == admission.generation) {
                    pendingImportJson = null
                    mutableUiState.update { state ->
                        state.copy(
                            pending = state.pending.copy(restoring = false)
                        )
                    }
                }
            }
        }
    }

    fun submitNewExpense(input: NewExpenseInput) {
        val ticket = entityMutationTicket() ?: return
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = RefreshScope.HOME,
            isDuplicatePending = { it.entrySaving },
            refreshDomains = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER),
            nextGeneration = { ++entryMutationGeneration },
            isCurrentGeneration = { entryMutationGeneration == it },
            setPending = { pending, value -> pending.copy(entrySaving = value) },
            validationMessage = {
                UiMessage("请检查金额、名称和分类").takeUnless {
                    input.isValidForNewExpense(uiState.value.categories)
                }
            },
            mutation = {
                if (input.categoryExplicit) repository.addExpense(input.amountCents, input.categoryKey, input.name.trim(), input.note.trim())
                else repository.addExpense(input.copy(name = input.name.trim(), note = input.note.trim()))
            },
            translateResult = { saved ->
                if (saved) {
                    ForegroundMutationResult(committed = true) { state ->
                        if (state.overlay == ExpenseOverlay.Entry) {
                            state.copy(overlay = null)
                        } else {
                            state
                        }
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = UiMessage("请检查金额、名称和分类")
                    )
                }
            },
            failureMessage = UiMessage("保存失败，请重试")
        )
    }

    fun updateExpense(id: Long, input: ExpenseEditInput) {
        val searchEdit = uiState.value.search.editingExpense?.takeIf { it.expense.id == id }
        val original = searchEdit?.expense ?: (uiState.value.expenses + uiState.value.history.items).find { it.id == id }
        val ticket = (if (searchEdit != null) coordinator.mutationTicket(searchEdit.epoch) else entityMutationTicket()) ?: return
        val refreshScope = if (input.date != null || input.time != null) RefreshScope.ALL else expenseMutationRefreshScope(id)
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = refreshScope,
            isDuplicatePending = { it.expenseEditSaving },
            refreshDomains = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER),
            nextGeneration = { ++expenseEditMutationGeneration },
            isCurrentGeneration = { expenseEditMutationGeneration == it },
            setPending = { pending, value -> pending.copy(expenseEditSaving = value) },
            validationMessage = {
                UiMessage("名称不能为空，备注最多 100 个字").takeUnless {
                    input.isValidForExpenseEdit(uiState.value.categories)
                }
            },
            mutation = {
                if (!input.categoryExplicit && input.isSpecial == null) repository.updateExpense(id, input.name.trim(), input.note.trim(), input.categoryKey, input.date, input.time)
                else repository.updateExpense(id, input.copy(name = input.name.trim(), note = input.note.trim()))
            },
            translateResult = { saved ->
                if (saved) {
                    ForegroundMutationResult(committed = true) { state ->
                        val edit = state.overlay as? ExpenseOverlay.EditExpense
                        val updated = if (edit?.expenseId == id) {
                            if (searchEdit != null) state.copy(overlay = ExpenseOverlay.Search,
                                search = state.search.copy(editingExpense = null))
                            else state.copy(overlay = if (edit.returnToSpecialBudget) ExpenseOverlay.SpecialBudgetDetails else null)
                        } else {
                            state
                        }
                        if (original != null && !original.isSpecial && input.isSpecial != true &&
                            (input.categoryExplicit || original.category.key != input.categoryKey))
                            updated.copy(categoryCorrection = CategoryCorrection(input.categoryKey, original.merchant, input.name.trim(), ticket.epoch))
                        else updated
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = UiMessage("名称不能为空，备注最多 100 个字")
                    )
                }
            },
            failureMessage = UiMessage("保存失败，请重试")
        )
    }

    fun dismissCategoryCorrection() { mutableUiState.update { it.copy(categoryCorrection = null) } }

    fun deleteExpense(id: Long) {
        val ticket = entityMutationTicket() ?: return
        val refreshScope = expenseMutationRefreshScope(id)
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = refreshScope,
            isDuplicatePending = { it.expenseEditSaving },
            refreshDomains = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER),
            nextGeneration = { ++expenseEditMutationGeneration },
            isCurrentGeneration = { expenseEditMutationGeneration == it },
            setPending = { pending, value -> pending.copy(expenseEditSaving = value) },
            mutation = { repository.deleteExpense(id) },
            translateResult = {
                ForegroundMutationResult(committed = true) { state ->
                    state.copy(
                        expenses = state.expenses.filterNot { it.id == id },
                        history = state.history.copy(
                            items = state.history.items.filterNot { it.id == id }
                        )
                    )
                }
            },
            failureMessage = UiMessage("删除失败，请重试")
        )
    }

    fun showCategoryEditor(target: CategoryEditorTarget) {
        mutableUiState.update { state ->
            state.copy(categoryManagement = state.categoryManagement.copy(editor = target))
        }
    }

    fun requestCategoryDelete(categoryKey: String) {
        mutableUiState.update { state ->
            state.copy(categoryManagement = state.categoryManagement.copy(deleteTargetKey = categoryKey))
        }
    }

    fun dismissCategoryEditor() {
        mutableUiState.update { state ->
            state.copy(categoryManagement = state.categoryManagement.copy(editor = null))
        }
    }

    fun dismissCategoryDelete() {
        mutableUiState.update { state ->
            state.copy(categoryManagement = state.categoryManagement.copy(deleteTargetKey = null))
        }
    }

    fun addCategory(input: CategoryInput) {
        val ticket = entityMutationTicket() ?: return
        val name = input.name?.trim().orEmpty()
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = categoryMutationRefreshScope(),
            isDuplicatePending = { it.categorySaving || it.categoryDeleting },
            nextGeneration = { ++categoryMutationGeneration },
            isCurrentGeneration = { categoryMutationGeneration == it },
            setPending = { pending, value -> pending.copy(categorySaving = value) },
            mutation = { repository.addCustomCategory(name, input.colorArgb) },
            translateResult = { result ->
                if (result is CategoryMutationResult.Success) {
                    ForegroundMutationResult(committed = true) { state ->
                        if (state.categoryManagement.editor == CategoryEditorTarget.New) {
                            state.copy(
                                categoryManagement = state.categoryManagement.copy(editor = null)
                            )
                        } else {
                            state
                        }
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = categoryMutationMessage(result)
                    )
                }
            },
            failureMessage = UiMessage("分类保存失败，请重试")
        )
    }

    fun updateCategory(categoryKey: String, input: CategoryInput) {
        val ticket = entityMutationTicket() ?: return
        val category = uiState.value.categories.firstOrNull { it.key == categoryKey }
        val name = if (category?.builtIn == true) null else input.name?.trim()
        val editorTarget = CategoryEditorTarget.Existing(categoryKey)
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = categoryMutationRefreshScope(),
            isDuplicatePending = { it.categorySaving || it.categoryDeleting },
            nextGeneration = { ++categoryMutationGeneration },
            isCurrentGeneration = { categoryMutationGeneration == it },
            setPending = { pending, value -> pending.copy(categorySaving = value) },
            mutation = { repository.updateCategory(categoryKey, name, input.colorArgb) },
            translateResult = { result ->
                if (result is CategoryMutationResult.Success) {
                    ForegroundMutationResult(committed = true) { state ->
                        if (state.categoryManagement.editor == editorTarget) {
                            state.copy(
                                categoryManagement = state.categoryManagement.copy(editor = null)
                            )
                        } else {
                            state
                        }
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = categoryMutationMessage(result)
                    )
                }
            },
            failureMessage = UiMessage("分类保存失败，请重试")
        )
    }

    fun deleteCategory(categoryKey: String) {
        val ticket = entityMutationTicket() ?: return
        val categoryName = uiState.value.categories.firstOrNull { it.key == categoryKey }?.name.orEmpty()
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = categoryMutationRefreshScope(),
            isDuplicatePending = { it.categorySaving || it.categoryDeleting },
            nextGeneration = { ++categoryMutationGeneration },
            isCurrentGeneration = { categoryMutationGeneration == it },
            setPending = { pending, value -> pending.copy(categoryDeleting = value) },
            mutation = { repository.deleteCustomCategory(categoryKey) },
            translateResult = { result ->
                if (result == CategoryDeleteResult.Deleted) {
                    ForegroundMutationResult(
                        committed = true,
                        message = categoryDeleteMessage(result, categoryName)
                    ) { state ->
                        if (state.categoryManagement.deleteTargetKey == categoryKey) {
                            state.copy(
                                categoryManagement = state.categoryManagement.copy(
                                    deleteTargetKey = null
                                )
                            )
                        } else {
                            state
                        }
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = categoryDeleteMessage(result, categoryName)
                    )
                }
            },
            failureMessage = UiMessage("分类删除失败，请重试")
        )
    }

    fun setBudget(kind: BudgetKind, amountCents: Long) {
        val ticket = entityMutationTicket() ?: return
        launchForegroundMutation(
            ticket = ticket,
            refreshScope = RefreshScope.HOME,
            isDuplicatePending = { it.budgetSaving },
            refreshDomains = setOf(com.example.monthlyexpense.data.DataDomain.SETTINGS),
            nextGeneration = { ++budgetMutationGeneration },
            isCurrentGeneration = { budgetMutationGeneration == it },
            setPending = { pending, value -> pending.copy(budgetSaving = value) },
            validationMessage = {
                UiMessage("预算金额无效").takeUnless {
                    amountCents in 0..MoneyLimits.MAX_CENTS
                }
            },
            mutation = {
                when (kind) {
                    BudgetKind.MONTHLY -> repository.setMonthlyBudget(
                        YearMonth.from(today()),
                        amountCents
                    )
                    BudgetKind.DAILY -> {
                        repository.setDailyBudget(amountCents)
                        true
                    }
                }
            },
            translateResult = { saved ->
                if (saved) {
                    ForegroundMutationResult(committed = true) { state ->
                        if (state.overlay == ExpenseOverlay.EditBudget(kind)) {
                            state.copy(overlay = null)
                        } else {
                            state
                        }
                    }
                } else {
                    ForegroundMutationResult(
                        committed = false,
                        message = UiMessage("预算金额无效")
                    )
                }
            },
            failureMessage = UiMessage("预算保存失败，请重试")
        )
    }

    private fun <T> launchForegroundMutation(
        ticket: ForegroundMutationTicket,
        refreshScope: RefreshScope,
        refreshDomains: Set<com.example.monthlyexpense.data.DataDomain> = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER, com.example.monthlyexpense.data.DataDomain.SETTINGS),
        isDuplicatePending: (ExpensePendingState) -> Boolean,
        nextGeneration: () -> Long,
        isCurrentGeneration: (Long) -> Boolean,
        setPending: (ExpensePendingState, Boolean) -> ExpensePendingState,
        validationMessage: () -> UiMessage? = { null },
        mutation: suspend () -> T,
        translateResult: (T) -> ForegroundMutationResult,
        failureMessage: UiMessage
    ) {
        val generation = synchronized(foregroundMutationAdmissionLock) {
            val state = mutableUiState.value
            if (isDuplicatePending(state.pending)) return
            val admittedGeneration = nextGeneration()
            mutableUiState.value = state.copy(
                pending = setPending(state.pending, true)
            )
            admittedGeneration
        }
        val isCurrent = { isCurrentGeneration(generation) }
        val invalidMessage = try {
            validationMessage()
        } catch (error: Throwable) {
            updatePendingIfCurrent(isCurrent) { setPending(it, false) }
            throw error
        }
        if (invalidMessage != null) {
            emitMessageIfCurrent(ticket, isCurrent, invalidMessage)
            updatePendingIfCurrent(isCurrent) { setPending(it, false) }
            return
        }
        viewModelScope.launch {
            try {
                when (val admission = coordinator.runMutation(ticket, mutation)) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> {
                        val result = translateResult(admission.value)
                        result.message?.let {
                            emitMessageIfCurrent(ticket, isCurrent, it)
                        }
                        if (result.committed) {
                            val current = updateStateIfCurrent(
                                ticket,
                                isCurrent,
                                result.reduceState
                            )
                            if (current) {
                                refreshAfterCommittedMutation(
                                    ticket,
                                    isCurrent,
                                    refreshScope,
                                    refreshDomains
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitMessageIfCurrent(ticket, isCurrent, failureMessage)
            } finally {
                updatePendingIfCurrent(isCurrent) { setPending(it, false) }
            }
        }
    }

    private fun entityMutationTicket(): ForegroundMutationTicket? =
        coordinator.mutationTicket(uiState.value.displayedDataEpoch)

    private fun launchHistoryPageLoad(admission: HistoryLoadAdmission) {
        viewModelScope.launch {
            try {
                when (val read = coordinator.runRead(admission.ticket) {
                    withContext(ioDispatcher) {
                        repository.loadHistoryPage(admission.cursor, HISTORY_PAGE_SIZE, admission.month)
                    }
                }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> coordinator.commitIfCurrent(admission.ticket) {
                        if (historyLoadGeneration == admission.generation) {
                            mutableUiState.update { state ->
                                state.copy(
                                    history = state.history.withPage(
                                        cursor = admission.cursor,
                                        page = read.value
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.commitIfCurrent(admission.ticket) {
                    if (historyLoadGeneration == admission.generation) {
                        mutableUiState.update { state ->
                            state.copy(
                                history = state.history.withLoadFailure(admission.cursor)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun clearExportPendingIfCurrent(generation: Long) {
        if (exportMutationGeneration != generation) return
        pendingDocumentWrite = null
        mutableUiState.update { state ->
            state.copy(
                pending = state.pending.copy(exportPreparing = false)
            )
        }
    }

    private fun clearImportConfirmation() {
        pendingImportJson = null
        mutableUiState.update { state ->
            if (state.overlay == ExpenseOverlay.ConfirmRestore) {
                state.copy(overlay = null)
            } else {
                state
            }
        }
    }

    private suspend fun loadAndApplyRestoreSnapshot() {
        val refreshDate = today()
        val historyPlan = prepareHistoryRefresh(RefreshScope.ALL, refreshDate)
        val autoGeneration = autoBookkeepingMutationGeneration
        val weChatGeneration = weChatMutationGeneration
        val alipayGeneration = alipayMutationGeneration
        val refreshRead = try {
            loadRefreshRead(refreshDate, historyPlan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failHistoryRefresh(historyPlan)
            throw error
        }
        applyHomeSnapshot(
            snapshot = refreshRead.homeSnapshot,
            appliedDate = refreshRead.date,
            appliedZone = refreshRead.zone,
            notificationState = uiState.value.notificationState,
            displayedDataEpoch = coordinator.currentEpoch,
            appliedMonth = YearMonth.from(refreshDate),
            autoBookkeepingGenerationAtLoadStart = autoGeneration,
            weChatGenerationAtLoadStart = weChatGeneration,
            alipayGenerationAtLoadStart = alipayGeneration,
            history = historyAfterRefresh(refreshRead.historyRead, historyPlan)
        )
        (refreshRead.historyRead as? HistoryPageRead.Failed)?.let { throw it.error }
    }

    private fun applyPersistedSettings() {
        val baseline = coordinator.persistedSettings
        updateAutoBookkeeping { settings ->
            settings.copy(
                enabled = baseline.autoBookkeepingEnabled,
                weChatEnabled = baseline.weChatEnabled,
                alipayEnabled = baseline.alipayEnabled,
                enabledWritePending = false,
                weChatWritePending = false,
                alipayWritePending = false
            )
        }
    }

    private fun exportCompletionMessage(
        format: BackupExportFormat,
        success: Boolean
    ): UiMessage = UiMessage(
        when (format) {
            BackupExportFormat.JSON -> if (success) {
                "JSON 备份已导出"
            } else {
                "JSON 备份导出失败"
            }
            BackupExportFormat.CSV -> if (success) {
                "CSV 表格已导出"
            } else {
                "CSV 表格导出失败"
            }
        }
    )

    private fun restoreOutcomeMessage(outcome: RestoreOutcome): String = when (outcome) {
        RestoreOutcome.SETTINGS_PENDING -> "账本已恢复，设置尚未恢复完成，请继续恢复设置。"
        RestoreOutcome.NOT_RESTORED -> "恢复失败，当前数据未更改"
        RestoreOutcome.NOT_RESTORED_REFRESH_FAILED -> "恢复失败，当前数据未更改"
        RestoreOutcome.RESTORED -> "备份恢复完成"
        RestoreOutcome.RESTORED_REFRESH_FAILED -> "操作已保存，但页面刷新失败"
    }

    private fun updatePendingIfCurrent(
        isCurrentGeneration: () -> Boolean,
        transform: (ExpensePendingState) -> ExpensePendingState
    ) {
        mutableUiState.update { state ->
            if (isCurrentGeneration()) state.copy(pending = transform(state.pending)) else state
        }
    }

    private fun updateStateIfCurrent(
        ticket: ForegroundMutationTicket,
        isCurrentGeneration: () -> Boolean,
        transform: (ExpenseUiState) -> ExpenseUiState
    ): Boolean {
        var updated = false
        coordinator.commitIfCurrent(ticket) {
            mutableUiState.update { state ->
                if (isCurrentGeneration()) {
                    updated = true
                    transform(state)
                } else {
                    state
                }
            }
        }
        return updated
    }

    private suspend fun refreshAfterCommittedMutation(
        ticket: ForegroundMutationTicket,
        isCurrentGeneration: () -> Boolean,
        refreshScope: RefreshScope,
        domains: Set<com.example.monthlyexpense.data.DataDomain>
    ) {
        val waiter = CompletableDeferred<RefreshResult>()
        enqueueRefresh(
            QueuedRefresh(
                request = RefreshRequest(
                    scope = refreshScope,
                    notificationState = uiState.value.notificationState,
                    domains = domains
                ),
                waiters = listOf(waiter),
                reportPageFailure = false,
                notificationStateIsExternal = false
            )
        )
        if (waiter.await() == RefreshResult.FAILED) {
            emitMessageIfCurrent(
                ticket,
                isCurrentGeneration,
                "操作已保存，但页面刷新失败"
            )
        }
    }

    private fun enqueueRefresh(entry: QueuedRefresh) {
        val first = refreshMerger.offer(entry) ?: return
        viewModelScope.launch { drainRefreshes(first) }
    }

    private suspend fun drainRefreshes(first: QueuedRefresh) {
        var current: QueuedRefresh? = first
        var cancelled = false
        try {
            while (current != null) {
                val active = current.copy(request = current.request.copy(domains = current.request.domains + failedRefreshDomains))
                val result = performRefresh(active)
                if (result == RefreshResult.FAILED) failedRefreshDomains = failedRefreshDomains + active.request.domains
                else if (result == RefreshResult.APPLIED) failedRefreshDomains = failedRefreshDomains - active.request.domains
                active.waiters.forEach { waiter -> waiter.complete(result) }
                if (
                    result == RefreshResult.FAILED &&
                    active.reportPageFailure &&
                    active.waiters.isEmpty()
                ) {
                    emitMessage(UiMessage("页面刷新失败，请重试"))
                }
                current = refreshMerger.nextOrFinish()
            }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            if (cancelled) {
                val pending = refreshMerger.cancelDrain()
                current?.waiters?.forEach { waiter ->
                    waiter.complete(RefreshResult.STALE)
                }
                pending?.waiters?.forEach { waiter ->
                    waiter.complete(RefreshResult.STALE)
                }
            }
        }
    }

    private suspend fun performRefresh(entry: QueuedRefresh): RefreshResult {
        val ticket = coordinator.readTicket() ?: return RefreshResult.STALE
        val refreshDate = today()
        val historyPlan = when (
            val prepared = coordinator.commitReadIfCurrent(ticket) {
                prepareHistoryRefresh(entry.request.scope, refreshDate)
            }
        ) {
            CoordinatedMutation.Rejected -> return RefreshResult.STALE
            is CoordinatedMutation.Executed -> prepared.value
        }
        return try {
            when (val read = coordinator.runRead(ticket) {
                val autoGeneration = autoBookkeepingMutationGeneration
                val weChatGeneration = weChatMutationGeneration
                val alipayGeneration = alipayMutationGeneration
                RefreshReadWithSettingGenerations(
                    read = loadRefreshRead(refreshDate, historyPlan, entry.request.domains + failedRefreshDomains),
                    autoBookkeeping = autoGeneration,
                    weChat = weChatGeneration,
                    alipay = alipayGeneration
                )
            }) {
                CoordinatedMutation.Rejected -> RefreshResult.STALE
                is CoordinatedMutation.Executed -> when (
                    val commit = coordinator.commitReadIfCurrent(ticket) { displayedDataEpoch ->
                        applyHomeSnapshot(
                            snapshot = read.value.read.homeSnapshot,
                            appliedDate = read.value.read.date,
                            appliedZone = read.value.read.zone,
                            notificationState = if (entry.notificationStateIsExternal) {
                                entry.request.notificationState
                            } else {
                                uiState.value.notificationState
                            },
                            displayedDataEpoch = displayedDataEpoch,
                            appliedMonth = YearMonth.from(refreshDate),
                            autoBookkeepingGenerationAtLoadStart = read.value.autoBookkeeping,
                            weChatGenerationAtLoadStart = read.value.weChat,
                            alipayGenerationAtLoadStart = read.value.alipay,
                            history = historyAfterRefresh(
                                read.value.read.historyRead,
                                historyPlan
                            )
                        )
                        if (read.value.read.historyRead is HistoryPageRead.Failed) {
                            RefreshResult.FAILED
                        } else {
                            RefreshResult.APPLIED
                        }
                    }
                ) {
                    CoordinatedMutation.Rejected -> RefreshResult.STALE
                    is CoordinatedMutation.Executed -> commit.value
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coordinator.commitReadIfCurrent(ticket) { failHistoryRefresh(historyPlan) }
            if (coordinator.isReadCurrent(ticket)) {
                RefreshResult.FAILED
            } else {
                RefreshResult.STALE
            }
        }
    }

    private fun categoryMutationRefreshScope(): RefreshScope =
        if (uiState.value.history.initialLoadComplete) {
            RefreshScope.ALL
        } else {
            RefreshScope.HOME
        }

    private fun expenseMutationRefreshScope(id: Long): RefreshScope =
        if (uiState.value.history.items.any { it.id == id }) {
            RefreshScope.ALL
        } else {
            RefreshScope.HOME
        }

    private fun prepareHistoryRefresh(
        requestedScope: RefreshScope,
        refreshDate: LocalDate
    ): HistoryRefreshPlan {
        val state = mutableUiState.value
        val monthRollover = lastAppliedMonth != null &&
            lastAppliedMonth != YearMonth.from(refreshDate)
        val effectiveScope = if (
            requestedScope == RefreshScope.HOME &&
            monthRollover &&
            state.history.initialLoadComplete
        ) {
            RefreshScope.ALL
        } else {
            requestedScope
        }
        if (effectiveScope == RefreshScope.HOME) {
            if (monthRollover) {
                historyLoadGeneration++
                mutableUiState.value = state.copy(history = HistoryUiState())
            }
            return HistoryRefreshPlan(
                generation = null,
                loadFirstPage = false,
                previous = state.history
            )
        }

        val generation = ++historyLoadGeneration
        val loadFirstPage = state.overlay == ExpenseOverlay.History ||
            state.history.initialLoadComplete
        val history = if (loadFirstPage) {
            state.history.copy(
                loading = true,
                loadingMore = false,
                loadFailed = false,
                failedCursor = null
            )
        } else {
            HistoryUiState()
        }
        mutableUiState.value = state.copy(history = history)
        return HistoryRefreshPlan(
            generation = generation,
            loadFirstPage = loadFirstPage,
            previous = state.history
        )
    }

    private suspend fun loadRefreshRead(
        refreshDate: LocalDate,
        historyPlan: HistoryRefreshPlan,
        domains: Set<com.example.monthlyexpense.data.DataDomain> = setOf(com.example.monthlyexpense.data.DataDomain.LEDGER, com.example.monthlyexpense.data.DataDomain.SETTINGS)
    ): RefreshRead {
        val refreshZone = zoneId()
        val homeSnapshot = withContext(ioDispatcher) {
            val baseline = coordinator.persistedSettings
            val previous = lastHomeSnapshot?.copy(autoBookkeepingEnabled = baseline.autoBookkeepingEnabled,
                weChatEnabled = baseline.weChatEnabled, alipayEnabled = baseline.alipayEnabled)
            if (coordinator.isDurableRestorePending) {
                repository.loadReadOnlyHomeSnapshot(refreshDate, refreshZone)
            } else if (previous == null || !uiState.value.initialLoadComplete || lastAppliedDate != refreshDate || lastAppliedZone != refreshZone) {
                repository.loadHomeSnapshot(refreshDate, refreshZone)
            } else repository.refreshHomeSnapshot(refreshDate, refreshZone, previous, domains)
        }
        val historyRead = if (historyPlan.loadFirstPage) {
            try {
                HistoryPageRead.Loaded(
                    withContext(ioDispatcher) {
                        repository.loadHistoryPage(null, HISTORY_PAGE_SIZE, historyPlan.previous.selectedMonth)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                HistoryPageRead.Failed(error)
            }
        } else {
            HistoryPageRead.NotRequested
        }
        return RefreshRead(homeSnapshot, historyRead, refreshDate, refreshZone)
    }

    private fun historyAfterRefresh(
        historyRead: HistoryPageRead,
        historyPlan: HistoryRefreshPlan
    ): HistoryUiState {
        val current = mutableUiState.value.history
        if (
            historyPlan.generation != null &&
            historyLoadGeneration != historyPlan.generation
        ) {
            return current
        }
        return when (historyRead) {
            HistoryPageRead.NotRequested -> current
            is HistoryPageRead.Loaded -> current.withPage(null, historyRead.page)
            is HistoryPageRead.Failed -> current.withLoadFailure(null)
        }
    }

    private fun failHistoryRefresh(historyPlan: HistoryRefreshPlan) {
        val generation = historyPlan.generation ?: return
        if (historyLoadGeneration != generation) return
        mutableUiState.update { state ->
            state.copy(
                history = if (historyPlan.loadFirstPage) {
                    historyPlan.previous.withLoadFailure(null)
                } else {
                    HistoryUiState()
                }
            )
        }
    }

    private fun emitMessage(message: UiMessage) {
        eventChannel.trySend(UiEvent.ShowMessage(message))
    }

    private fun emitMessageIfCurrent(
        ticket: ForegroundMutationTicket,
        isCurrentGeneration: () -> Boolean,
        message: String
    ) {
        emitMessageIfCurrent(ticket, isCurrentGeneration, UiMessage(message))
    }

    private fun emitMessageIfCurrent(
        ticket: ForegroundMutationTicket,
        isCurrentGeneration: () -> Boolean,
        message: UiMessage
    ) {
        coordinator.commitIfCurrent(ticket) {
            if (isCurrentGeneration()) emitMessage(message)
        }
    }

    private fun categoryMutationMessage(result: CategoryMutationResult): UiMessage? = when (result) {
        is CategoryMutationResult.Success -> null
        CategoryMutationResult.DuplicateName -> UiMessage("分类名称已存在")
        CategoryMutationResult.InvalidName -> UiMessage("分类名称应为 1 至 20 个字符")
        CategoryMutationResult.BuiltInNameLocked -> UiMessage("内置分类名称不能修改")
        CategoryMutationResult.NotFound -> UiMessage("分类不存在")
    }

    private fun categoryDeleteMessage(
        result: CategoryDeleteResult,
        categoryName: String
    ): UiMessage? = when (result) {
        CategoryDeleteResult.Deleted -> UiMessage("分类“$categoryName”已删除")
        is CategoryDeleteResult.InUse -> UiMessage(
            "该分类仍有 ${result.expenseCount} 条消费记录，请先编辑这些记录并更改分类"
        )
        CategoryDeleteResult.BuiltInLocked -> UiMessage("内置分类不能删除")
        CategoryDeleteResult.NotFound -> UiMessage("分类不存在")
    }

    fun setAutoBookkeepingEnabled(enabled: Boolean) {
        val ticket = coordinator.mutationTicket() ?: return
        val generation = ++autoBookkeepingMutationGeneration
        updateAutoBookkeeping {
            it.copy(enabled = enabled, enabledWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { autoBookkeepingMutationGeneration == generation },
            write = {
                repository.setAutoBookkeepingEnabled(enabled)
                coordinator.updatePersistedSettings(
                    coordinator.persistedSettings.copy(autoBookkeepingEnabled = enabled)
                )
            },
            rollbackAndClearPending = { settings, baseline ->
                settings.copy(
                    enabled = baseline.autoBookkeepingEnabled,
                    enabledWritePending = false
                )
            },
            clearPending = { it.copy(enabledWritePending = false) },
            failureMessage = "自动记账设置保存失败"
        )
    }

    fun setWeChatEnabled(enabled: Boolean) {
        val ticket = coordinator.mutationTicket() ?: return
        val generation = ++weChatMutationGeneration
        updateAutoBookkeeping {
            it.copy(weChatEnabled = enabled, weChatWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { weChatMutationGeneration == generation },
            write = {
                repository.setWeChatEnabled(enabled)
                coordinator.updatePersistedSettings(
                    coordinator.persistedSettings.copy(weChatEnabled = enabled)
                )
            },
            rollbackAndClearPending = { settings, baseline ->
                settings.copy(
                    weChatEnabled = baseline.weChatEnabled,
                    weChatWritePending = false
                )
            },
            clearPending = { it.copy(weChatWritePending = false) },
            failureMessage = "微信自动记账设置保存失败"
        )
    }

    fun setAlipayEnabled(enabled: Boolean) {
        val ticket = coordinator.mutationTicket() ?: return
        val generation = ++alipayMutationGeneration
        updateAutoBookkeeping {
            it.copy(alipayEnabled = enabled, alipayWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { alipayMutationGeneration == generation },
            write = {
                repository.setAlipayEnabled(enabled)
                coordinator.updatePersistedSettings(
                    coordinator.persistedSettings.copy(alipayEnabled = enabled)
                )
            },
            rollbackAndClearPending = { settings, baseline ->
                settings.copy(
                    alipayEnabled = baseline.alipayEnabled,
                    alipayWritePending = false
                )
            },
            clearPending = { it.copy(alipayWritePending = false) },
            failureMessage = "支付宝自动记账设置保存失败"
        )
    }

    private fun launchSettingWrite(
        ticket: ForegroundMutationTicket,
        isCurrentGeneration: () -> Boolean,
        write: suspend () -> Unit,
        rollbackAndClearPending: (
            AutoBookkeepingUiState,
            ForegroundSettingsBaseline
        ) -> AutoBookkeepingUiState,
        clearPending: (AutoBookkeepingUiState) -> AutoBookkeepingUiState,
        failureMessage: String
    ) {
        val writeFailure = AtomicReference<Exception?>(null)
        val writeJob = viewModelScope.launch {
            try {
                coordinator.runMutation(ticket, write)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                writeFailure.set(error)
            }
        }
        writeJob.invokeOnCompletion { cause ->
            when {
                cause is CancellationException -> {
                    updateAutoBookkeepingIfCurrent(isCurrentGeneration) { settings ->
                        rollbackAndClearPending(settings, coordinator.persistedSettings)
                    }
                }
                writeFailure.get() != null -> {
                    val rolledBack = updateAutoBookkeepingIfCurrent(
                        isCurrentGeneration
                    ) { settings ->
                        rollbackAndClearPending(settings, coordinator.persistedSettings)
                    }
                    if (rolledBack && coordinator.isCurrent(ticket)) {
                        eventChannel.trySend(UiEvent.ShowMessage(UiMessage(failureMessage)))
                    }
                }
                else -> updateAutoBookkeepingIfCurrent(
                    isCurrentGeneration,
                    clearPending
                )
            }
        }
    }

    private fun updateAutoBookkeepingIfCurrent(
        isCurrentGeneration: () -> Boolean,
        transform: (AutoBookkeepingUiState) -> AutoBookkeepingUiState
    ): Boolean {
        var updated = false
        mutableUiState.update { currentState ->
            if (isCurrentGeneration()) {
                updated = true
                currentState.copy(
                    autoBookkeeping = transform(currentState.autoBookkeeping)
                )
            } else {
                updated = false
                currentState
            }
        }
        return updated
    }

    private fun updateAutoBookkeeping(
        transform: (AutoBookkeepingUiState) -> AutoBookkeepingUiState
    ) {
        mutableUiState.update { currentState ->
            currentState.copy(
                autoBookkeeping = transform(currentState.autoBookkeeping)
            )
        }
    }

    private fun applyHomeSnapshot(
        snapshot: ExpenseHomeSnapshot,
        appliedDate: LocalDate,
        appliedZone: ZoneId,
        notificationState: NotificationListenerConnectionState,
        displayedDataEpoch: Long,
        appliedMonth: YearMonth,
        autoBookkeepingGenerationAtLoadStart: Long,
        weChatGenerationAtLoadStart: Long,
        alipayGenerationAtLoadStart: Long,
        history: HistoryUiState
    ) = com.example.monthlyexpense.notification.NotificationMetrics.measure(com.example.monthlyexpense.notification.PipelineMetric.UI_APPLY) {
        coordinator.updatePersistedSettings(snapshot.settingsBaseline())
        lastHomeSnapshot = snapshot
        lastAppliedDate = appliedDate
        lastAppliedZone = appliedZone
        lastAppliedMonth = appliedMonth
        val currentState = mutableUiState.value
        val currentSettings = currentState.autoBookkeeping
        mutableUiState.value = currentState.copy(
            expenses = snapshot.expenses,
            history = history,
            categories = snapshot.categories,
            monthlyBudgetCents = snapshot.monthlyBudgetCents,
            dailyBudgetCents = snapshot.dailyBudgetCents,
            todayTotalCents = snapshot.todayTotalCents,
            notificationState = notificationState,
            autoBookkeeping = currentSettings.copy(
                enabled = snapshot.autoBookkeepingEnabled.takeIf {
                    !currentSettings.enabledWritePending &&
                        autoBookkeepingMutationGeneration ==
                        autoBookkeepingGenerationAtLoadStart
                } ?: currentSettings.enabled,
                weChatEnabled = snapshot.weChatEnabled.takeIf {
                    !currentSettings.weChatWritePending &&
                        weChatMutationGeneration == weChatGenerationAtLoadStart
                } ?: currentSettings.weChatEnabled,
                alipayEnabled = snapshot.alipayEnabled.takeIf {
                    !currentSettings.alipayWritePending &&
                        alipayMutationGeneration == alipayGenerationAtLoadStart
                } ?: currentSettings.alipayEnabled
            ),
            displayedDataEpoch = displayedDataEpoch,
            initialLoadComplete = true
        )
    }

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

private data class ForegroundMutationResult(
    val committed: Boolean,
    val message: UiMessage? = null,
    val reduceState: (ExpenseUiState) -> ExpenseUiState = { it }
)

private data class PendingDocumentWrite(
    val format: BackupExportFormat,
    val ticket: ForegroundMutationTicket,
    val generation: Long
)

private data class RestoreAdmission(
    val ticket: ForegroundRestoreTicket,
    val generation: Long,
    val json: String
)

private data class HistoryLoadAdmission(
    val ticket: ForegroundMutationTicket,
    val generation: Long,
    val cursor: HistoryCursor?,
    val month: YearMonth? = null
)

private data class HistoryRefreshPlan(
    val generation: Long?,
    val loadFirstPage: Boolean,
    val previous: HistoryUiState
)

private data class RefreshRead(
    val homeSnapshot: ExpenseHomeSnapshot,
    val historyRead: HistoryPageRead,
    val date: LocalDate,
    val zone: ZoneId
)

private data class RefreshReadWithSettingGenerations(
    val read: RefreshRead,
    val autoBookkeeping: Long,
    val weChat: Long,
    val alipay: Long
)

private sealed interface HistoryPageRead {
    data object NotRequested : HistoryPageRead

    data class Loaded(val page: HistoryPage) : HistoryPageRead

    data class Failed(val error: Exception) : HistoryPageRead
}

private fun HistoryUiState.withPage(
    cursor: HistoryCursor?,
    page: HistoryPage
): HistoryUiState {
    val publishedItems = if (cursor == null) {
        val pageIds = hashSetOf<Long>()
        page.items.filter { pageIds.add(it.id) }
    } else {
        val existingIds = items.asSequence().map { it.id }.toHashSet()
        val appended = page.items.filter { existingIds.add(it.id) }
        items + appended
    }
    return copy(
        months = page.months,
        items = publishedItems,
        nextCursor = page.nextCursor,
        initialLoadComplete = true,
        loading = false,
        loadingMore = false,
        hasMore = page.hasMore,
        loadFailed = false,
        failedCursor = null
    )
}

private fun HistoryUiState.withLoadFailure(cursor: HistoryCursor?): HistoryUiState = copy(
    initialLoadComplete = true,
    loading = false,
    loadingMore = false,
    loadFailed = true,
    failedCursor = cursor
)

private fun ExpenseHomeSnapshot.settingsBaseline(): ForegroundSettingsBaseline =
    ForegroundSettingsBaseline(
        autoBookkeepingEnabled = autoBookkeepingEnabled,
        weChatEnabled = weChatEnabled,
        alipayEnabled = alipayEnabled
    )

private fun NewExpenseInput.isValidForNewExpense(categories: List<ExpenseCategory>): Boolean {
    val normalizedName = name.trim()
    val normalizedNote = note.trim()
    return amountCents in 1..MoneyLimits.MAX_CENTS &&
        normalizedName.isNotEmpty() &&
        normalizedName.length <= 40 &&
        normalizedNote.length <= 100 &&
        categories.any { it.key == categoryKey }
}

private fun ExpenseEditInput.isValidForExpenseEdit(categories: List<ExpenseCategory>): Boolean {
    val normalizedName = name.trim()
    val normalizedNote = note.trim()
    return normalizedName.isNotEmpty() &&
        normalizedName.length <= 40 &&
        normalizedNote.length <= 100 &&
        categories.any { it.key == categoryKey }
}
