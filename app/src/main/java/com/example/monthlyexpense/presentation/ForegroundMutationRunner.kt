package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.UiMessage
import com.example.monthlyexpense.data.CoordinatedMutation
import com.example.monthlyexpense.data.DataDomain
import com.example.monthlyexpense.data.ForegroundMutationTicket
import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.presentation.contracts.MutationResult
import com.example.monthlyexpense.presentation.contracts.MutationSlot
import com.example.monthlyexpense.presentation.contracts.UiEventSink
import com.example.monthlyexpense.refresh.RefreshResult
import com.example.monthlyexpense.refresh.RefreshScope
import com.example.monthlyexpense.refresh.api.MutationRefreshPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared persistence skeleton; does not know feature states, repositories or business result types.
 * Commands use the host UI thread; admission and completion use the host's shared lock (completion
 * may run on a cancellation thread). Lock order is admission -> coordinator, never the reverse. */
internal class ForegroundMutationRunner(
    private val scope: CoroutineScope,
    private val coordinator: ForegroundPersistenceCoordinator,
    private val admissionLock: Any,
    private val refresh: MutationRefreshPort,
    private val events: UiEventSink,
) {
    /** Ticket is captured by the command before calling, never renewed here. Validation is synchronous;
     * exceptions propagate after cleanup. translate is pure; applyCommitted is a synchronous UI effect.
     * Persistence failures emit failureMessage; cancellation is silent and propagates. Pending remains
     * until this mutation's refresh finishes. No durable gate or restore ownership is transferred. */
    fun <T> launch(
        ticket: ForegroundMutationTicket,
        slot: MutationSlot,
        validationMessage: () -> UiMessage?,
        mutation: suspend () -> T,
        translate: (T) -> MutationResult,
        applyCommitted: (T) -> Unit,
        refreshScope: RefreshScope,
        refreshDomains: Set<DataDomain>,
        failureMessage: UiMessage,
    ) {
        val generation = synchronized(admissionLock) { slot.admit() } ?: return
        fun finish() = synchronized(admissionLock) { slot.finish(generation) }
        fun commitCurrent(effect: () -> Unit): Boolean = synchronized(admissionLock) {
            val result = coordinator.commitIfCurrent(ticket) {
                if (slot.isCurrent(generation)) { effect(); true } else false
            }
            result is CoordinatedMutation.Executed && result.value
        }
        fun message(value: UiMessage) { commitCurrent { events.emit(UiEvent.ShowMessage(value)) } }
        val invalid = try { validationMessage() } catch (error: Throwable) { finish(); throw error }
        if (invalid != null) { message(invalid); finish(); return }
        scope.launch {
            try {
                when (val admitted = coordinator.runMutation(ticket, mutation)) {
                    CoordinatedMutation.Rejected -> Unit
                    is CoordinatedMutation.Executed -> {
                        val result = translate(admitted.value)
                        result.message?.let(::message)
                        if (result.committed && commitCurrent { applyCommitted(admitted.value) }) {
                            if (refresh.afterMutation(refreshScope, refreshDomains) == RefreshResult.FAILED) {
                                message(UiMessage("操作已保存，但页面刷新失败"))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message(failureMessage)
            }
        }.invokeOnCompletion { finish() }
    }
}
