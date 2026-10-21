package com.example.monthlyexpense.architecture

import com.example.monthlyexpense.data.ForegroundPersistenceCoordinator
import com.example.monthlyexpense.data.ForegroundRestoreTicket
import com.example.monthlyexpense.data.RestoreCommit
import com.example.monthlyexpense.refresh.HomeRefreshController
import com.example.monthlyexpense.refresh.api.RestoreRefreshPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class LegalRestoreCaller {
    suspend fun confirmRestore(coordinator: ForegroundPersistenceCoordinator, ticket: ForegroundRestoreTicket, port: RestoreRefreshPort) {
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, { port.refreshWithinRestore() })
    }
}

internal class ConcreteEscapeRestoreCaller {
    suspend fun confirmRestore(coordinator: ForegroundPersistenceCoordinator, ticket: ForegroundRestoreTicket, port: RestoreRefreshPort) {
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, { port.refreshWithinRestore() })
    }
    suspend fun outsideRestore(home: HomeRefreshController) { home.refreshWithinRestore() }
}

internal class WrongCoroutineRestoreCaller {
    suspend fun confirmRestore(coordinator: ForegroundPersistenceCoordinator, ticket: ForegroundRestoreTicket,
        port: RestoreRefreshPort, scope: CoroutineScope) {
        scope.launch { port.refreshWithinRestore() }
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, {})
    }
}

internal class NestedCoroutineRestoreCaller {
    suspend fun confirmRestore(coordinator: ForegroundPersistenceCoordinator, ticket: ForegroundRestoreTicket,
        port: RestoreRefreshPort, scope: CoroutineScope) {
        coordinator.runRestore(ticket, { RestoreCommit.NotRestored }, { scope.launch { port.refreshWithinRestore() } })
    }
}

internal class WrongArgumentRestoreCaller {
    suspend fun confirmRestore(coordinator: ForegroundPersistenceCoordinator, ticket: ForegroundRestoreTicket, port: RestoreRefreshPort) {
        coordinator.runRestore(ticket, { port.refreshWithinRestore(); RestoreCommit.NotRestored }, {})
    }
}
