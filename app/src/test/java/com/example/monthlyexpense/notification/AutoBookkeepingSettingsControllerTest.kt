package com.example.monthlyexpense.notification

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.notification.api.AutoSettingsWritePort
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

/** Plain JUnit: no Android ViewModel, AppContainer, full repository, or database. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBookkeepingSettingsControllerTest {
    private class State : SliceState<AutoBookkeepingUiState> {
        val flow = MutableStateFlow(AutoBookkeepingUiState())
        override val value get() = flow.value
        override fun update(transform: (AutoBookkeepingUiState) -> AutoBookkeepingUiState) = flow.update(transform)
    }
    private class Writes : AutoSettingsWritePort {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var action: suspend (String, Boolean) -> Unit = { _, _ -> }
        private suspend fun write(key: String, value: Boolean) { calls += key to value; action(key, value) }
        override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) = write("auto", enabled)
        override suspend fun setWeChatEnabled(enabled: Boolean) = write("wechat", enabled)
        override suspend fun setAlipayEnabled(enabled: Boolean) = write("alipay", enabled)
    }
    private class Fixture(scope: CoroutineScope) {
        val state = State()
        val writes = Writes()
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
        val events = mutableListOf<UiEvent>()
        val controller = AutoBookkeepingSettingsController(scope, state, writes, coordinator,
            UiEventSink { events += it; true })
    }

    @Test fun olderFailureCannotRollbackNewChoiceOrClearItsPending() = runTest {
        val f = Fixture(this)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        f.writes.action = { _, _ ->
            if (f.writes.calls.size == 1) { first.await(); throw IllegalStateException("old failure") }
            else second.await()
        }
        f.controller.setAutoBookkeepingEnabled(true)
        runCurrent()
        f.controller.setAutoBookkeepingEnabled(false)
        first.complete(Unit)
        runCurrent()
        assertFalse(f.state.value.enabled)
        assertTrue(f.state.value.enabledWritePending)
        assertTrue(f.events.isEmpty())
        second.complete(Unit)
        advanceUntilIdle()
        assertFalse(f.state.value.enabledWritePending)
        assertEquals(listOf("auto" to true, "auto" to false), f.writes.calls)
        assertFalse(f.coordinator.persistedSettings.autoBookkeepingEnabled)
    }

    @Test fun cancellationCleanupCannotClearReentrantNewerWrite() = runTest {
        val f = Fixture(this)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        f.writes.action = { _, _ ->
            if (f.writes.calls.size == 1) { first.await(); throw CancellationException("old cancellation") }
            else second.await()
        }
        var armed = false
        var restarted = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.state.flow.collect {
                if (armed && !it.enabled && !restarted) {
                    restarted = true
                    f.controller.setAutoBookkeepingEnabled(true)
                }
            }
        }
        f.controller.setAutoBookkeepingEnabled(true)
        runCurrent()
        armed = true
        first.complete(Unit)
        runCurrent()
        assertTrue(restarted)
        assertTrue(f.state.value.enabled)
        assertTrue(f.state.value.enabledWritePending)
        assertTrue(f.events.isEmpty())
        second.complete(Unit)
        advanceUntilIdle()
        assertTrue(f.state.value.enabled)
        assertFalse(f.state.value.enabledWritePending)
    }

    @Test fun staleSnapshotCannotOverrideNewCompletedWriteOrPendingIndependentSwitch() = runTest {
        val f = Fixture(this)
        val captured = f.controller.captureGenerations()
        f.controller.setAutoBookkeepingEnabled(true)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.writes.action = { _, _ -> gate.await() }
        f.controller.setWeChatEnabled(false)
        runCurrent()
        val before = f.state.value
        val snapshot = ExpenseHomeSnapshot(emptyList(), emptyList(), 0, 0, 0, false, true, false)
        val merged = f.controller.mergeSnapshot(snapshot, captured)
        assertTrue(merged.enabled)
        assertFalse(merged.weChatEnabled)
        assertTrue(merged.weChatWritePending)
        assertFalse(merged.alipayEnabled)
        assertEquals("Merge computes a value without publishing it", before, f.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun currentFailureRollsBackOnlyItsSwitchAndEmitsItsMessage() = runTest {
        val f = Fixture(this)
        f.writes.action = { _, _ -> throw IllegalStateException("write failed") }
        f.controller.setAutoBookkeepingEnabled(true)
        f.controller.setWeChatEnabled(false)
        f.controller.setAlipayEnabled(false)
        advanceUntilIdle()
        assertEquals(AutoBookkeepingUiState(), f.state.value)
        assertEquals(listOf("自动记账设置保存失败", "微信自动记账设置保存失败", "支付宝自动记账设置保存失败")
            .map { UiEvent.ShowMessage(UiMessage(it)) }, f.events)
    }

    @Test fun alreadyCancelledScopeCleansPendingWithoutLaunchingCleanupOrWriting() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        val f = Fixture(scope)
        f.controller.setAutoBookkeepingEnabled(true)
        f.controller.setWeChatEnabled(false)
        f.controller.setAlipayEnabled(false)
        advanceUntilIdle()
        assertEquals(AutoBookkeepingUiState(), f.state.value)
        assertTrue(f.writes.calls.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test fun restoreInvalidationRejectsOldCompletionWithoutPublishingPartialReset() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.writes.action = { _, _ -> gate.await(); throw IllegalStateException("old write") }
        f.controller.setAutoBookkeepingEnabled(true)
        runCurrent()
        val before = f.state.value
        requireNotNull(f.coordinator.beginRestore())
        f.controller.invalidateForRestore()
        assertEquals("Invalidation must not publish a partial UI reset", before, f.state.value)
        val baseline = ForegroundSettingsBaseline(false, false, false)
        f.coordinator.updatePersistedSettings(baseline)
        f.state.update { f.controller.restoredState(baseline) }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AutoBookkeepingUiState(enabled = false, weChatEnabled = false, alipayEnabled = false), f.state.value)
        assertTrue(f.events.isEmpty())
    }

    @Test fun durableRestoreGateRejectsEverySwitchWithoutOptimisticChange() = runTest {
        val f = Fixture(this)
        f.coordinator.setDurableRestorePending(true)
        f.controller.setAutoBookkeepingEnabled(true)
        f.controller.setWeChatEnabled(false)
        f.controller.setAlipayEnabled(false)
        advanceUntilIdle()
        assertEquals(AutoBookkeepingUiState(), f.state.value)
        assertTrue(f.writes.calls.isEmpty())
    }

    @Test fun workerThreadCancellationCannotClearNewerPending() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val f = Fixture(scope)
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            f.writes.action = { _, _ -> if (f.writes.calls.size == 1) first.await() else second.await() }
            f.controller.setAutoBookkeepingEnabled(true)
            f.controller.setAutoBookkeepingEnabled(false)
            val worker = thread { first.completeExceptionally(CancellationException("worker cancellation")) }
            worker.join(5000)
            assertFalse("Worker cleanup must not deadlock", worker.isAlive)
            assertFalse(f.state.value.enabled)
            assertTrue(f.state.value.enabledWritePending)
            assertTrue(f.events.isEmpty())
            second.complete(Unit)
            assertFalse(f.state.value.enabledWritePending)
        } finally {
            scope.cancel()
        }
    }
}
