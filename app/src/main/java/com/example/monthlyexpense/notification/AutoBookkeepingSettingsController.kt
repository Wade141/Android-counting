package com.example.monthlyexpense.notification

import com.example.monthlyexpense.AutoBookkeepingUiState
import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.ExpenseHomeSnapshot
import com.example.monthlyexpense.data.ForegroundMutationTicket
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundSettingsBaseline
import com.example.monthlyexpense.notification.api.AutoSettingsWritePort
import com.example.monthlyexpense.notification.api.SettingGenerations
import com.example.monthlyexpense.notification.api.SettingsSnapshotPort
import com.example.monthlyexpense.presentation.contracts.SliceState
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** ViewModel-scoped settings logic; only its own slice and write capabilities are visible. */
internal class AutoBookkeepingSettingsController(
    private val scope: CoroutineScope,
    private val state: SliceState<AutoBookkeepingUiState>,
    private val writes: AutoSettingsWritePort,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val events: UiEventSink,
) : SettingsSnapshotPort {
    // Completion handlers can run outside the UI thread, even after the scope is cancelled.
    private val autoBookkeepingMutationGeneration = AtomicLong()
    private val weChatMutationGeneration = AtomicLong()
    private val alipayMutationGeneration = AtomicLong()

    fun setAutoBookkeepingEnabled(enabled: Boolean) {
        val ticket = coordinator.mutationTicket() ?: return
        val generation = autoBookkeepingMutationGeneration.incrementAndGet()
        updateAutoBookkeeping {
            it.copy(enabled = enabled, enabledWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { autoBookkeepingMutationGeneration.get() == generation },
            write = {
                writes.setAutoBookkeepingEnabled(enabled)
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
        val generation = weChatMutationGeneration.incrementAndGet()
        updateAutoBookkeeping {
            it.copy(weChatEnabled = enabled, weChatWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { weChatMutationGeneration.get() == generation },
            write = {
                writes.setWeChatEnabled(enabled)
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
        val generation = alipayMutationGeneration.incrementAndGet()
        updateAutoBookkeeping {
            it.copy(alipayEnabled = enabled, alipayWritePending = true)
        }
        launchSettingWrite(
            ticket = ticket,
            isCurrentGeneration = { alipayMutationGeneration.get() == generation },
            write = {
                writes.setAlipayEnabled(enabled)
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
        val writeJob = scope.launch {
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
                        events.emit(UiEvent.ShowMessage(UiMessage(failureMessage)))
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
        transform: (AutoBookkeepingUiState) -> AutoBookkeepingUiState,
    ): Boolean {
        state.update { current -> if (isCurrentGeneration()) transform(current) else current }
        // A synchronous observer may already have started a newer write during publication.
        return isCurrentGeneration()
    }

    private fun updateAutoBookkeeping(transform: (AutoBookkeepingUiState) -> AutoBookkeepingUiState) =
        state.update(transform)

    override fun captureGenerations() = SettingGenerations(autoBookkeepingMutationGeneration.get(),
        weChatMutationGeneration.get(), alipayMutationGeneration.get())

    override fun mergeSnapshot(snapshot: ExpenseHomeSnapshot, captured: SettingGenerations): AutoBookkeepingUiState {
        val current = state.value
        return current.copy(
            enabled = snapshot.autoBookkeepingEnabled.takeIf {
                !current.enabledWritePending && autoBookkeepingMutationGeneration.get() == captured.autoBookkeeping
            } ?: current.enabled,
            weChatEnabled = snapshot.weChatEnabled.takeIf {
                !current.weChatWritePending && weChatMutationGeneration.get() == captured.weChat
            } ?: current.weChatEnabled,
            alipayEnabled = snapshot.alipayEnabled.takeIf {
                !current.alipayWritePending && alipayMutationGeneration.get() == captured.alipay
            } ?: current.alipayEnabled,
        )
    }

    /** Invalidates private operations only; the host publishes the complete restore reset. */
    fun invalidateForRestore() {
        autoBookkeepingMutationGeneration.incrementAndGet()
        weChatMutationGeneration.incrementAndGet()
        alipayMutationGeneration.incrementAndGet()
    }

    fun restoredState(baseline: ForegroundSettingsBaseline) = AutoBookkeepingUiState(
        enabled = baseline.autoBookkeepingEnabled,
        weChatEnabled = baseline.weChatEnabled,
        alipayEnabled = baseline.alipayEnabled,
    )

}
