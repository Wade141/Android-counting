package com.example.monthlyexpense.backup

import com.example.monthlyexpense.BackupExportFormat
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.backup.api.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import com.example.monthlyexpense.refresh.api.RestoreRefreshPort
import java.time.ZoneId
import kotlinx.coroutines.*

/** Session-only backup payloads/receipts and three generations. No root state, feature implementation,
 * ordinary refresh queue, saved-state storage or independent coordinator/scope. Commands use the UI
 * thread; admission and cancellation cleanup share the host lock, before coordinator state checks. */
internal class BackupController(
    private val scope: CoroutineScope,
    private val backup: ExpenseBackupContract,
    private val state: SliceState<BackupOperationState>,
    private val navigation: BackupNavigation,
    private val events: UiEventSink,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val restoreUi: RestoreUiBoundary,
    private val settings: BackupSettingsCommit,
    private val restoreRefresh: RestoreRefreshPort,
    private val admissionLock: Any,
    private val ioDispatcher: CoroutineDispatcher,
    private val zoneId: () -> ZoneId,
) {
    private var exportMutationGeneration = 0L
    private var importValidationGeneration = 0L
    private var restoreGeneration = 0L
    private var pendingImportJson: String? = null
    private var pendingDocumentWrite: PendingDocumentWrite? = null

    fun prepareExport(format: BackupExportFormat, targetUri: String): Long? = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket() ?: return@synchronized null
        if (state.value.exportPreparing) return@synchronized null
        val generation = ++exportMutationGeneration
        pendingDocumentWrite = null
        state.update { it.copy(exportPreparing = true) }
        scope.launch {
            try {
                when (val read = coordinator.runRead(ticket) {
                    withContext(ioDispatcher) {
                        when (format) {
                            BackupExportFormat.JSON -> backup.exportJson()
                            BackupExportFormat.CSV -> backup.exportCsv(zoneId())
                        }
                    }
                }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> commitCurrent(ticket, { exportMutationGeneration == generation }) {
                        val write = PendingDocumentWrite(format, ticket, generation)
                        // Install before enqueue: an unconfined consumer may acknowledge synchronously.
                        pendingDocumentWrite = write
                        try {
                            if (!events.emit(UiEvent.WriteDocument(generation, targetUri, read.value)) && pendingDocumentWrite == write)
                                pendingDocumentWrite = null
                        } catch (error: Throwable) {
                            if (pendingDocumentWrite == write) pendingDocumentWrite = null
                            throw error
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitIfCurrent(ticket, { exportMutationGeneration == generation }, exportCompletionMessage(format, false))
            }
        }.invokeOnCompletion {
            synchronized(admissionLock) {
                // Also runs if cancellation prevented the body from starting. An enqueued document
                // remains pending until its own receipt, not until this coroutine completes.
                if (pendingDocumentWrite?.generation != generation) clearExportPending(generation)
            }
        }
        generation
    }

    fun documentWriteCompleted(writeId: Long, success: Boolean) = synchronized(admissionLock) {
        val write = pendingDocumentWrite?.takeIf { it.generation == writeId } ?: return@synchronized
        commitCurrent(write.ticket, { exportMutationGeneration == write.generation && pendingDocumentWrite == write }) {
            pendingDocumentWrite = null
            state.update { it.copy(exportPreparing = false) }
            emitIfCurrent(write.ticket, { exportMutationGeneration == write.generation }, exportCompletionMessage(write.format, success))
        }
    }

    fun acceptImportJson(json: String) = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket() ?: return@synchronized
        val generation = ++importValidationGeneration
        scope.launch {
            try {
                when (val read = coordinator.runRead(ticket) { withContext(ioDispatcher) { backup.validateJson(json) } }) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> commitCurrent(ticket, { importValidationGeneration == generation }) {
                        if (read.value) {
                            pendingImportJson = json
                            navigation.showRestoreConfirmation()
                        } else {
                            clearImportConfirmation()
                            emitMessage(UiMessage("文件不是有效的 expense_report JSON 备份"))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                commitCurrent(ticket, { importValidationGeneration == generation }) {
                    clearImportConfirmation()
                    emitMessage(UiMessage("文件不是有效的 expense_report JSON 备份"))
                }
            }
        }
        Unit
    }

    /** User dismiss of the confirmation also invalidates in-flight validation. Synchronous, no IO. */
    fun dismissRestoreConfirmation() = synchronized(admissionLock) {
        importValidationGeneration++
        clearImportConfirmation()
    }

    fun importReadFailed() = synchronized(admissionLock) {
        val ticket = coordinator.mutationTicket() ?: return@synchronized
        commitCurrent(ticket, { true }) {
            importValidationGeneration++
            clearImportConfirmation()
            emitMessage(UiMessage("无法读取所选文件"))
        }
    }

    fun confirmRestore() {
        val admission = synchronized(admissionLock) {
            val json = pendingImportJson ?: return
            val ticket = coordinator.beginRestore() ?: return
            val generation = ++restoreGeneration
            exportMutationGeneration++
            importValidationGeneration++
            pendingDocumentWrite = null
            restoreUi.begin(coordinator.persistedSettings)
            RestoreAdmission(ticket, generation, json)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = withContext(NonCancellable) {
                    coordinator.runRestore(admission.ticket,
                        restore = {
                            when (val result = withContext(ioDispatcher) { backup.restoreDetailed(admission.json) }) {
                                BackupRestoreResult.NotCommitted -> RestoreCommit.NotRestored
                                BackupRestoreResult.SettingsPending -> RestoreCommit.SettingsPending
                                is BackupRestoreResult.Completed -> RestoreCommit.Restored(ForegroundSettingsBaseline(
                                    result.settings.autoBookkeepingEnabled, result.settings.weChatEnabled, result.settings.alipayEnabled))
                            }
                        },
                        refreshAfterRestore = { restoreRefresh.refreshWithinRestore() })
                }
                currentCoroutineContext().ensureActive()
                synchronized(admissionLock) {
                    if (restoreGeneration == admission.generation) {
                        settings.applyPersistedSettings(coordinator.persistedSettings)
                        emitMessage(UiMessage(restoreOutcomeMessage(outcome)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(admissionLock) {
                    if (restoreGeneration == admission.generation) emitMessage(UiMessage("恢复未完成，请检查当前账本后重试。"))
                }
            } finally {
                synchronized(admissionLock) {
                    if (restoreGeneration == admission.generation) {
                        pendingImportJson = null
                        state.update { it.copy(restoring = false) }
                    }
                }
            }
        }
    }

    private fun clearExportPending(generation: Long) {
        if (exportMutationGeneration != generation) return
        pendingDocumentWrite = null
        state.update { it.copy(exportPreparing = false) }
    }
    private fun clearImportConfirmation() {
        pendingImportJson = null
        navigation.closeRestoreConfirmationIfCurrent()
    }
    private fun commitCurrent(ticket: ForegroundMutationTicket, current: () -> Boolean, block: () -> Unit) {
        synchronized(admissionLock) { coordinator.commitIfCurrent(ticket) { if (current()) block() } }
    }
    private fun emitIfCurrent(ticket: ForegroundMutationTicket, current: () -> Boolean, message: UiMessage) =
        commitCurrent(ticket, current) { emitMessage(message) }
    private fun emitMessage(message: UiMessage) { events.emit(UiEvent.ShowMessage(message)) }
}

private data class PendingDocumentWrite(val format: BackupExportFormat, val ticket: ForegroundMutationTicket, val generation: Long)
private data class RestoreAdmission(val ticket: ForegroundRestoreTicket, val generation: Long, val json: String)

private fun exportCompletionMessage(format: BackupExportFormat, success: Boolean) = UiMessage(when (format) {
    BackupExportFormat.JSON -> if (success) "JSON 备份已导出" else "JSON 备份导出失败"
    BackupExportFormat.CSV -> if (success) "CSV 表格已导出" else "CSV 表格导出失败"
})
private fun restoreOutcomeMessage(outcome: RestoreOutcome): String = when (outcome) {
    RestoreOutcome.SETTINGS_PENDING -> "账本已恢复，设置尚未恢复完成，请继续恢复设置。"
    RestoreOutcome.NOT_RESTORED, RestoreOutcome.NOT_RESTORED_REFRESH_FAILED -> "恢复失败，当前数据未更改"
    RestoreOutcome.RESTORED -> "备份恢复完成"
    RestoreOutcome.RESTORED_REFRESH_FAILED -> "操作已保存，但页面刷新失败"
}
