package com.example.monthlyexpense.backup

import com.example.monthlyexpense.*
import com.example.monthlyexpense.backup.api.*
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.MutableSlice
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import com.example.monthlyexpense.refresh.api.RestoreRefreshPort
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupControllerTest {
    private class Data : ExpenseBackupContract {
        var exportCalls = 0; var restoreCalls = 0; var validationCalls = 0
        var valid = true; var error: Exception? = null; var exportError: Exception? = null
        var result: BackupRestoreResult = BackupRestoreResult.Completed(ExpenseBackupSettings(100, true, false, true))
        var restoredJson: String? = null; var csvZone: ZoneId? = null
        override fun exportJson(exportedAt: Long): String { exportCalls++; exportError?.let { throw it }; return "json-content" }
        override fun exportCsv(zoneId: ZoneId): String { exportCalls++; csvZone = zoneId; return "csv-content" }
        override fun validateJson(json: String): Boolean { validationCalls++; return valid }
        override fun restoreJsonWithSettings(json: String): ExpenseBackupSettings? = error("use detailed result")
        override fun restoreDetailed(json: String): BackupRestoreResult {
            restoreCalls++; restoredJson = json; error?.let { throw it }; return result
        }
    }
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val data = Data(); val state = MutableSlice(BackupOperationState())
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        val events = mutableListOf<UiEvent>(); var confirmation = false; var accepts = true
        var onWrite: (UiEvent.WriteDocument) -> Unit = {}
        var begins = 0; var reads = 0; var settings: ForegroundSettingsBaseline? = null
        var refresh: suspend () -> Unit = {}
        val controller = BackupController(scope, data, state, object : BackupNavigation {
            override fun showRestoreConfirmation() { confirmation = true }
            override fun closeRestoreConfirmationIfCurrent(): Boolean { val old = confirmation; confirmation = false; return old }
        }, UiEventSink { event ->
            if (accepts) { events += event; if (event is UiEvent.WriteDocument) onWrite(event) }; accepts
        }, coordinator, RestoreUiBoundary {
            assertTrue(coordinator.isRestoring); begins++; confirmation = false
            state.value = BackupOperationState(restoring = true)
        }, BackupSettingsCommit { settings = it }, object : RestoreRefreshPort {
            override suspend fun refreshWithinRestore() { reads++; assertTrue(coordinator.isRestoring); refresh() }
        }, Any(), dispatcher, { ZoneId.of("Asia/Shanghai") })
        fun messages() = events.filterIsInstance<UiEvent.ShowMessage>().map { it.message.text }
        fun writes() = events.filterIsInstance<UiEvent.WriteDocument>()
    }

    @Test fun eachExportEmitsOneDocumentAndOnlyWriterReceiptCompletesIt() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        for (format in BackupExportFormat.entries) {
            f.events.clear(); val id = f.controller.prepareExport(format, "content://test")!!
            assertNull(f.controller.prepareExport(format, "content://duplicate")); advanceUntilIdle()
            assertTrue(f.state.value.exportPreparing); assertTrue(f.messages().isEmpty())
            val write = f.writes().single(); assertEquals(id, write.writeId); assertEquals("content://test", write.targetUri)
            assertEquals(if (format == BackupExportFormat.JSON) "json-content" else "csv-content", write.content)
            f.controller.documentWriteCompleted(id, true); f.controller.documentWriteCompleted(id, true)
            assertFalse(f.state.value.exportPreparing); assertEquals(1, f.messages().size)
        }
        assertEquals(2, f.data.exportCalls); assertEquals(ZoneId.of("Asia/Shanghai"), f.data.csvZone)
    }

    @Test fun synchronousWriterReceiptDuringEnqueueIsNotLost() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.onWrite = { f.controller.documentWriteCompleted(it.writeId, true) }
        f.controller.prepareExport(BackupExportFormat.JSON, "uri"); advanceUntilIdle()
        assertFalse(f.state.value.exportPreparing); assertEquals(listOf("JSON 备份已导出"), f.messages())
    }

    @Test fun enqueueFailureAndPreStartCancellationDoNotLeaveAwaitingExport() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.accepts = false
        val id = f.controller.prepareExport(BackupExportFormat.CSV, "uri")!!; advanceUntilIdle()
        assertFalse(f.state.value.exportPreparing); f.controller.documentWriteCompleted(id, true); assertTrue(f.events.isEmpty())
        val job = SupervisorJob(); val cancelled = Fixture(CoroutineScope(StandardTestDispatcher(testScheduler) + job), StandardTestDispatcher(testScheduler))
        cancelled.controller.prepareExport(BackupExportFormat.JSON, "uri"); job.cancel(); advanceUntilIdle()
        assertFalse(cancelled.state.value.exportPreparing); assertEquals(0, cancelled.data.exportCalls); assertTrue(cancelled.events.isEmpty())
    }

    @Test fun failedExportAndFailedWriterKeepDistinctMessagesAndPermitRetry() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.data.exportError = IllegalStateException("read")
        f.controller.prepareExport(BackupExportFormat.JSON, "uri"); advanceUntilIdle()
        assertEquals(listOf("JSON 备份导出失败"), f.messages()); assertTrue(f.writes().isEmpty()); assertFalse(f.state.value.exportPreparing)
        f.data.exportError = null; val id = f.controller.prepareExport(BackupExportFormat.JSON, "uri")!!; advanceUntilIdle()
        f.controller.documentWriteCompleted(id, false); assertEquals(2, f.messages().size); assertFalse(f.state.value.exportPreparing)
    }

    @Test fun restoreInvalidatesOldReceiptWithoutCompletingNewSameFormatExport() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.controller.acceptImportJson("private-json"); advanceUntilIdle()
        val old = f.controller.prepareExport(BackupExportFormat.JSON, "old")!!; advanceUntilIdle()
        f.controller.confirmRestore(); assertTrue(f.state.value.restoring); assertFalse(f.state.value.exportPreparing)
        advanceUntilIdle(); f.events.clear()
        val next = f.controller.prepareExport(BackupExportFormat.JSON, "next")!!; advanceUntilIdle(); assertNotEquals(old, next)
        f.controller.documentWriteCompleted(old, true); assertTrue(f.state.value.exportPreparing); assertTrue(f.messages().isEmpty())
        f.controller.documentWriteCompleted(next, true); assertFalse(f.state.value.exportPreparing)
        assertEquals(listOf("JSON 备份已导出"), f.messages())
    }

    @Test fun cancellationOfConfirmationInvalidatesPendingValidationAndFreshControllerHasNoPayload() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.controller.acceptImportJson("first"); advanceUntilIdle(); assertTrue(f.confirmation)
        val gate = CompletableDeferred<Unit>()
        val holding = launch { f.coordinator.runMutation(f.coordinator.mutationTicket()!!) { gate.await() } }; runCurrent()
        f.controller.acceptImportJson("later"); runCurrent(); f.controller.dismissRestoreConfirmation()
        gate.complete(Unit); holding.join(); advanceUntilIdle(); assertFalse(f.confirmation)
        f.controller.confirmRestore(); advanceUntilIdle(); assertEquals(0, f.data.restoreCalls)
        val fresh = Fixture(this, StandardTestDispatcher(testScheduler))
        fresh.controller.confirmRestore(); fresh.controller.documentWriteCompleted(1, true); advanceUntilIdle()
        assertEquals(0, fresh.data.restoreCalls); assertTrue(fresh.events.isEmpty())
    }

    @Test fun invalidImportAndReadFailureClearPayloadAndKeepOriginalCopy() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.data.valid = false
        f.controller.acceptImportJson("invalid"); advanceUntilIdle(); assertFalse(f.confirmation)
        f.data.valid = true; f.controller.acceptImportJson("valid"); advanceUntilIdle(); assertTrue(f.confirmation)
        f.controller.importReadFailed(); f.controller.confirmRestore(); advanceUntilIdle()
        assertEquals(listOf("文件不是有效的 expense_report JSON 备份", "无法读取所选文件"), f.messages())
        assertEquals(0, f.data.restoreCalls)
    }

    @Test fun detailedRestoreResultsAndRefreshFailuresPreserveSettingsAndDurableGate() = runTest {
        for (result in listOf(BackupRestoreResult.NotCommitted, BackupRestoreResult.SettingsPending,
            BackupRestoreResult.Completed(ExpenseBackupSettings(100, true, false, true)))) for (fails in listOf(false, true)) {
            val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.data.result = result
            f.refresh = { if (fails) error("snapshot read") }
            f.controller.acceptImportJson("payload"); advanceUntilIdle(); f.controller.confirmRestore(); advanceUntilIdle()
            val expected = when(result) {
                BackupRestoreResult.NotCommitted -> "恢复失败，当前数据未更改"
                BackupRestoreResult.SettingsPending -> "账本已恢复，设置尚未恢复完成，请继续恢复设置。"
                is BackupRestoreResult.Completed -> if (fails) "操作已保存，但页面刷新失败" else "备份恢复完成"
            }
            assertEquals(listOf(expected), f.messages()); assertEquals(1, f.reads); assertEquals(1, f.begins)
            assertFalse(f.state.value.restoring); assertFalse(f.coordinator.isRestoring)
            assertEquals(result == BackupRestoreResult.SettingsPending, f.coordinator.isDurableRestorePending)
            assertEquals(if (result is BackupRestoreResult.Completed) ForegroundSettingsBaseline(true, false, true)
                else ForegroundSettingsBaseline(false, true, true), f.settings)
            f.controller.confirmRestore(); advanceUntilIdle(); assertEquals(1, f.data.restoreCalls)
        }
    }

    @Test fun ordinaryRestoreErrorAndCancellationReleaseStateAndDiscardPrivateJson() = runTest {
        for (error in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.data.error = error
            f.controller.acceptImportJson("payload"); advanceUntilIdle(); f.controller.confirmRestore(); advanceUntilIdle()
            f.controller.confirmRestore(); advanceUntilIdle()
            assertEquals(1, f.data.restoreCalls); assertFalse(f.state.value.restoring); assertFalse(f.coordinator.isRestoring)
            assertEquals(if (error is CancellationException) emptyList<String>() else listOf("恢复未完成，请检查当前账本后重试。"), f.messages())
        }
    }

    @Test fun alreadyAdmittedWriteFinishesBeforeRestoreWhileQueuedExportCannotEmit() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler)); f.controller.acceptImportJson("payload"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); var committed = false
        val holding = launch { f.coordinator.runMutation(f.coordinator.mutationTicket()!!) { gate.await(); committed = true } }; runCurrent()
        f.controller.prepareExport(BackupExportFormat.JSON, "obsolete"); runCurrent()
        f.controller.confirmRestore(); f.controller.confirmRestore()
        assertTrue(f.coordinator.isRestoring); assertEquals(1, f.begins); assertEquals(0, f.data.restoreCalls)
        gate.complete(Unit); holding.join(); advanceUntilIdle()
        assertTrue(committed); assertEquals(1, f.data.restoreCalls); assertTrue(f.writes().isEmpty()); assertFalse(f.state.value.exportPreparing)
    }

    @Test fun cancelledScopeStillFinishesAdmittedRestoreAndCancellationDuringRefreshIsSilent() = runTest {
        for (before in listOf(false, true)) {
            val job = SupervisorJob(); val f = Fixture(CoroutineScope(StandardTestDispatcher(testScheduler) + job), StandardTestDispatcher(testScheduler))
            f.controller.acceptImportJson("payload"); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); f.refresh = { gate.await() }
            if (before) job.cancel()
            f.controller.confirmRestore(); runCurrent(); if (!before) job.cancel()
            assertTrue(f.coordinator.isRestoring); assertTrue(f.state.value.restoring)
            gate.complete(Unit); advanceUntilIdle()
            assertFalse(f.coordinator.isRestoring); assertFalse(f.state.value.restoring); assertEquals(1, f.data.restoreCalls)
            assertTrue(f.messages().isEmpty())
        }
    }
}
