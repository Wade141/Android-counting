package com.example.monthlyexpense.presentation

import com.example.monthlyexpense.UiEvent
import com.example.monthlyexpense.data.*
import com.example.monthlyexpense.presentation.contracts.*
import com.example.monthlyexpense.refresh.*
import com.example.monthlyexpense.refresh.api.MutationRefreshPort
import kotlinx.coroutines.CoroutineScope

/** Test-only narrow state doubles and the real mutation/coordinator stack. No VM or repository. */
internal class MutableSlice<S>(override var value: S) : SliceState<S> {
    override fun update(transform: (S) -> S) { value = transform(value) }
}
internal class MutableRead<S>(override var value: S) : ReadState<S>
internal class MutationControllerFixture(scope: CoroutineScope) {
    val lock = Any()
    val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(false, true, true))
    val epoch = MutableRead(0L)
    val events = mutableListOf<UiEvent>()
    val refreshes = mutableListOf<Pair<RefreshScope, Set<DataDomain>>>()
    var refresh: suspend () -> RefreshResult = { RefreshResult.APPLIED }
    val runner = ForegroundMutationRunner(scope, coordinator, lock, object : MutationRefreshPort {
        override suspend fun afterMutation(scope: RefreshScope, domains: Set<DataDomain>): RefreshResult {
            refreshes += scope to domains; return refresh()
        }
    }, UiEventSink { events += it; true })
    fun messages() = events.filterIsInstance<UiEvent.ShowMessage>().map { it.message.text }
    suspend fun restore(reset: () -> Unit) {
        val ticket = synchronized(lock) { coordinator.beginRestore()!!.also { reset() } }
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {})
        epoch.value = coordinator.currentEpoch
    }
}
