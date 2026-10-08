package com.example.monthlyexpense.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import com.example.monthlyexpense.BackupExportFormat
import com.example.monthlyexpense.ExpenseViewModel
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.backup.ExpenseBackupContract
import com.example.monthlyexpense.backup.StrictUtf8
import com.example.monthlyexpense.data.ExpenseRepositoryContract
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.notification.NotificationListenerRecovery
import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import com.example.monthlyexpense.notification.replay.ConnectionStatus
import com.example.monthlyexpense.notification.replay.ReplayResultPresentation
import com.example.monthlyexpense.notification.work.NotificationReplayRequests
import com.example.monthlyexpense.notification.work.ReplayUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExpenseAppRoute(
    repository: ExpenseRepositoryContract,
    backupManager: ExpenseBackupContract,
    refreshSignal: Int,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    persistenceCoordinator: ForegroundPersistenceCoordinator? = null,
    dataChanges: com.example.monthlyexpense.data.DataChangePublisher? = null,
    connectionSignal: Int = 0,
    categoryRuleRepository: com.example.monthlyexpense.classification.CategoryRuleRepository? = null
) {
    val context = LocalContext.current
    val contentResolver = remember(context) { context.applicationContext.contentResolver }
    val coroutineScope = rememberCoroutineScope()
    val factory = remember(repository, backupManager, ioDispatcher, persistenceCoordinator) {
        ExpenseViewModel.factory(repository, backupManager, ioDispatcher,
            persistenceCoordinator ?: ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true)))
    }
    val expenseViewModel: ExpenseViewModel = viewModel(factory = factory)
    val state by expenseViewModel.uiState.collectAsStateWithLifecycle()
    val keywordViewModel: com.example.monthlyexpense.ui.categories.CategoryKeywordViewModel? = if (categoryRuleRepository == null) null else {
        val keywordFactory = remember(categoryRuleRepository, persistenceCoordinator) {
            androidx.lifecycle.viewmodel.viewModelFactory {
                initializer {
                    com.example.monthlyexpense.ui.categories.CategoryKeywordViewModel(categoryRuleRepository) { persistenceCoordinator?.currentEpoch ?: 0L }
                }
            }
        }
        viewModel(factory = keywordFactory)
    }
    val keywordState = keywordViewModel?.state?.collectAsStateWithLifecycle()?.value
        ?: com.example.monthlyexpense.ui.categories.CategoryKeywordsState()
    LaunchedEffect(state.overlay, state.displayedDataEpoch) { keywordViewModel?.refresh() }
    if (dataChanges != null) {
        val keywordRevision by dataChanges.revisions.collectAsStateWithLifecycle()
        LaunchedEffect(keywordRevision.settings) { keywordViewModel?.refresh() }
    }
    if (dataChanges != null) {
        val revisions by dataChanges.homeRefreshes.collectAsStateWithLifecycle()
        var previous by remember(dataChanges) { mutableStateOf(com.example.monthlyexpense.data.DataRevisions()) }
        LaunchedEffect(revisions.ledger, revisions.settings) {
            expenseViewModel.refreshDomains(revisions.changedSince(previous))
            previous = revisions
        }
        val allRevisions by dataChanges.revisions.collectAsStateWithLifecycle()
        var searchPrevious by remember(dataChanges) { mutableStateOf(com.example.monthlyexpense.data.DataRevisions()) }
        LaunchedEffect(allRevisions.ledger, allRevisions.settings) {
            expenseViewModel.onSearchDataChanged(allRevisions.changedSince(searchPrevious))
            searchPrevious = allRevisions
        }
    }
    LaunchedEffect(connectionSignal) {
        expenseViewModel.updateNotificationState(NotificationListenerRecovery.forContext(context).connectionState)
    }
    val replayRequests = remember(context) { NotificationReplayRequests(context) }
    val replayState by replayRequests.state.collectAsStateWithLifecycle(initialValue = ReplayUiState())
    var replaySubmitting by remember { mutableStateOf(false) }
    var replaySubmitError by remember { mutableStateOf<String?>(null) }

    fun requestReplay(repair: Boolean, retryOperationId: String? = null) {
        if (replaySubmitting || replayState.busy || state.pending.restoring) return
        replaySubmitting = true
        coroutineScope.launch {
            try {
                replayRequests.request(repair, retryOperationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                replaySubmitError = "暂时无法开始补记，请稍后重试。"
            } finally {
                replaySubmitting = false
            }
        }
    }
    val backgroundViewModel: com.example.monthlyexpense.ui.background.BackgroundViewModel = viewModel()
    val backgroundState by backgroundViewModel.state.collectAsStateWithLifecycle()
    val appearanceViewModel: com.example.monthlyexpense.ui.settings.AppearanceViewModel = viewModel()
    val appearanceState by appearanceViewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) backgroundViewModel.select(uri)
    }
    val platformEvents = remember { Channel<UiEvent>(Channel.BUFFERED) }
    val events = remember(expenseViewModel, platformEvents) {
        merge(expenseViewModel.events, platformEvents.receiveAsFlow())
    }
    var pendingDocumentFormatName by rememberSaveable { mutableStateOf<String?>(null) }
    var documentEffectGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var pendingDocumentWriteId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun prepareDocumentExport(format: BackupExportFormat, uri: Uri) {
        val writeId = expenseViewModel.prepareExport(format, uri.toString()) ?: return
        documentEffectGeneration++
        pendingDocumentFormatName = format.name
        pendingDocumentWriteId = writeId
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && !state.pending.exportPreparing) {
            prepareDocumentExport(BackupExportFormat.JSON, uri)
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && !state.pending.exportPreparing) {
            prepareDocumentExport(BackupExportFormat.CSV, uri)
        }
    }
    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val json = try {
                    withContext(ioDispatcher) { contentResolver.readUtf8(uri) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (json == null) {
                    expenseViewModel.importReadFailed()
                } else {
                    expenseViewModel.acceptImportJson(json)
                }
            }
        }
    }

    ExpenseRefreshEffect(refreshSignal) {
        expenseViewModel.onSearchEnvironmentChanged(java.time.LocalDate.now(), java.time.ZoneId.systemDefault())
        expenseViewModel.refresh(
            NotificationListenerRecovery.forContext(context).connectionState
        )
    }

    ExpenseAppScreen(
        categoryRules = keywordState.rules,
        state = state,
        background = backgroundState.saved,
        fontColor = appearanceState.color,
        cardAppearance = appearanceState.cards,
        notificationOperationBusy = replaySubmitting || replayState.busy,
        notificationOperationProgress = replayState.progress,
        backgroundOverlay = {
            if (settingsOpen && !backgroundState.editorOpen) {
                com.example.monthlyexpense.ui.settings.SettingsDialog(
                    state = appearanceState, background = backgroundState.saved,
                    onClose = { settingsOpen = false },
                    onOpenFont = appearanceViewModel::openEditor,
                    onCancelFont = appearanceViewModel::cancel,
                    onApplyFont = appearanceViewModel::save,
                    onBackground = backgroundViewModel::open,
                    onOpenCard = appearanceViewModel::openCardEditor,
                    onApplyCard = appearanceViewModel::saveCards,
                    onCancelCard = appearanceViewModel::cancel
                )
            }
            ExpenseAppTheme {
            com.example.monthlyexpense.ui.background.BackgroundEditor(
                state = backgroundState,
                onChoose = { backgroundPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClose = backgroundViewModel::close,
                onCancelPreview = backgroundViewModel::cancelPreview,
                onMove = backgroundViewModel::move,
                onApply = backgroundViewModel::apply,
                onReset = backgroundViewModel::reset
            )
            }
        },
        callbacks = ExpenseAppCallbacks(
            onOpenSearch = expenseViewModel::openSearch,
            search = com.example.monthlyexpense.ui.search.ExpenseSearchCallbacks(
                onClose = expenseViewModel::closeSearch,
                onKeyword = expenseViewModel::setSearchKeyword,
                onSubmit = expenseViewModel::submitSearch,
                onOpenFilters = expenseViewModel::openSearchFilters,
                onDraft = expenseViewModel::updateSearchFilterDraft,
                onCancelFilters = expenseViewModel::cancelSearchFilters,
                onResetDraft = expenseViewModel::resetSearchFilterDraft,
                onApplyFilters = expenseViewModel::applySearchFilters,
                onReplaceFilters = expenseViewModel::replaceSearchFilters,
                onClear = expenseViewModel::clearSearch,
                onMore = expenseViewModel::loadMoreSearch,
                onRetry = expenseViewModel::retrySearch,
                onEdit = expenseViewModel::openSearchExpense
            ),
            onOpenSettings = { expenseViewModel.dismissOverlay(); settingsOpen = true },
            onShowOverlay = expenseViewModel::showOverlay,
            onOpenHistory = expenseViewModel::openHistory,
            onSelectHistoryMonth = expenseViewModel::selectHistoryMonth,
            onLoadMoreHistory = expenseViewModel::loadMoreHistory,
            onRetryHistory = expenseViewModel::retryHistoryLoad,
            onDismissOverlay = expenseViewModel::dismissOverlay,
            onSubmitNewExpense = expenseViewModel::submitNewExpense,
            onUpdateExpense = expenseViewModel::updateExpense,
            onDeleteExpense = expenseViewModel::deleteExpense,
            onShowCategoryEditor = expenseViewModel::showCategoryEditor,
            onOpenCategoryKeywords = { key, prefill, sourceEpoch -> keywordViewModel?.open(key, prefill, sourceEpoch) },
            onDismissCorrection = expenseViewModel::dismissCategoryCorrection,
            onDismissCategoryEditor = expenseViewModel::dismissCategoryEditor,
            onRequestCategoryDelete = expenseViewModel::requestCategoryDelete,
            onDismissCategoryDelete = expenseViewModel::dismissCategoryDelete,
            onAddCategory = expenseViewModel::addCategory,
            onUpdateCategory = expenseViewModel::updateCategory,
            onDeleteCategory = expenseViewModel::deleteCategory,
            onSetBudget = expenseViewModel::setBudget,
            onSetAutoBookkeepingEnabled = expenseViewModel::setAutoBookkeepingEnabled,
            onSetWeChatEnabled = expenseViewModel::setWeChatEnabled,
            onSetAlipayEnabled = expenseViewModel::setAlipayEnabled,
            onRequestNotificationAccess = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onReplayRecent = { requestReplay(false) },
            onRepairNotifications = { requestReplay(true) },
            onExportJson = {
                if (!state.pending.exportPreparing && !state.pending.restoring) {
                    exportJsonLauncher.launch("expense_report.json")
                }
            },
            onExportCsv = {
                if (!state.pending.exportPreparing && !state.pending.restoring) {
                    exportCsvLauncher.launch("expense_report.csv")
                }
            },
            onImportJson = {
                importJsonLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/json",
                        "text/plain",
                        "application/octet-stream"
                    )
                )
            },
            onConfirmRestore = expenseViewModel::confirmRestore
        )
    )
    if (keywordViewModel != null) ExpenseAppTheme(appearanceState.color, appearanceState.cards) {
        keywordState.editor?.let { editor ->
            com.example.monthlyexpense.ui.categories.CategoryKeywordScreen(editor, state.categories,
                com.example.monthlyexpense.ui.categories.CategoryKeywordCallbacks(
                    cancel = keywordViewModel::cancel, save = keywordViewModel::save,
                    input = keywordViewModel::input, add = keywordViewModel::addKeyword,
                    mode = keywordViewModel::mode, enabled = keywordViewModel::enabled, field = keywordViewModel::field,
                    edit = keywordViewModel::editKeyword, remove = keywordViewModel::remove,
                    merchant = keywordViewModel::testMerchant, name = keywordViewModel::testName,
                    preview = keywordViewModel::preview, openConflict = { keywordViewModel.open(it) }))
        }
        keywordState.error?.let { error ->
            androidx.compose.material3.AlertDialog(onDismissRequest = keywordViewModel::dismissError,
                text = { androidx.compose.material3.Text(error) },
                confirmButton = { androidx.compose.material3.TextButton(keywordViewModel::dismissError) { androidx.compose.material3.Text("知道了") } })
        }
    }
    val liveConnection = when (state.notificationState) {
        NotificationListenerConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
        NotificationListenerConnectionState.DISCONNECTED, NotificationListenerConnectionState.NOT_AUTHORIZED -> ConnectionStatus.DISCONNECTED
        NotificationListenerConnectionState.RECOVERY_FAILED -> ConnectionStatus.FAILED
        NotificationListenerConnectionState.RECOVERING -> ConnectionStatus.CONNECTING
    }
    val presentation = replayState.operation?.let { ReplayResultPresentation.from(it, liveConnection) }
    val replayMessage = replaySubmitError ?: replayState.message
    if (replayMessage != null || presentation != null) {
        ExpenseAppTheme(appearanceState.color, appearanceState.cards) {
            androidx.compose.runtime.key(replaySubmitError ?: replayState.resultId) {
                if (replayMessage != null) NotificationReplayPopup(replayMessage, replaySubmitError == null && replayState.completed) {
                    if (replaySubmitError != null) replaySubmitError = null
                    else replayState.resultId?.let(replayRequests::dismiss)
                }
                else if (presentation != null) NotificationReplayPopup(presentation,
                    onRetry = if (presentation.canRetry && !replayState.busy) ({ requestReplay(false, presentation.operationId) }) else null,
                    onDismiss = { replayRequests.dismiss(presentation.operationId) })
            }
        }
    }

    ExpenseAppTheme {
        ExpenseUiEventHost(
            events = events,
            documentEffect = { writeId ->
                pendingDocumentWriteId
                    ?.takeIf { it == writeId }
                    ?.let { pendingWriteId ->
                        pendingDocumentFormatName?.let { formatName ->
                            DocumentEffect(
                                format = BackupExportFormat.valueOf(formatName),
                                generation = documentEffectGeneration,
                                writeId = pendingWriteId
                            )
                        }
                    }
            },
            documentWriter = { targetUri, content ->
                withContext(ioDispatcher) {
                    contentResolver.writeUtf8(Uri.parse(targetUri), content)
                    true
                }
            },
            onDocumentWriteCompleted = { completedEffect, success ->
                val currentEffect = pendingDocumentWriteId?.let { pendingWriteId ->
                    pendingDocumentFormatName?.let { formatName ->
                        DocumentEffect(
                            format = BackupExportFormat.valueOf(formatName),
                            generation = documentEffectGeneration,
                            writeId = pendingWriteId
                        )
                    }
                }
                if (
                    currentEffect.dispatchCompletionIfCurrent(
                        completedEffect = completedEffect,
                        success = success,
                        onCurrentCompletion = expenseViewModel::documentWriteCompleted
                    )
                ) {
                    pendingDocumentFormatName = null
                    pendingDocumentWriteId = null
                }
            }
        )
    }
}

@Composable
internal fun ExpenseRefreshEffect(refreshSignal: Int, onRefresh: () -> Unit) {
    LaunchedEffect(refreshSignal) {
        if (refreshSignal != 0) onRefresh()
    }
}

internal fun DocumentEffect?.dispatchCompletionIfCurrent(
    completedEffect: DocumentEffect,
    success: Boolean,
    onCurrentCompletion: (Long, Boolean) -> Unit
): Boolean {
    if (this != completedEffect) return false
    onCurrentCompletion(completedEffect.writeId, success)
    return true
}

private fun android.content.ContentResolver.writeUtf8(uri: Uri, content: String) {
    openOutputStream(uri, "rwt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
        ?: error("Unable to open destination")
}

private fun android.content.ContentResolver.readUtf8(uri: Uri): String {
    val bytes = openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BACKUP_BYTES) { "Backup file is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unable to open source")
    return StrictUtf8.decode(bytes)
}

private const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
