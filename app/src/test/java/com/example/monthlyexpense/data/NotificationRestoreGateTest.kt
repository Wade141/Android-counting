package com.example.monthlyexpense.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationRestoreGateTest {
    @Test fun pendingSettingsBlocksOldAndNewMutationsUntilReconciled() = runTest {
        val coordinator = ForegroundPersistenceCoordinator(ForegroundSettingsBaseline(true, true, true))
        val old = requireNotNull(coordinator.mutationTicket())
        coordinator.setDurableRestorePending(true)
        assertNull(coordinator.mutationTicket())
        assertEquals(CoordinatedMutation.Rejected, coordinator.runMutation(old) { error("must not write") })
        coordinator.setDurableRestorePending(false)
        assertFalse(coordinator.isCurrent(old))
        assertNotNull(coordinator.mutationTicket())
    }
}
