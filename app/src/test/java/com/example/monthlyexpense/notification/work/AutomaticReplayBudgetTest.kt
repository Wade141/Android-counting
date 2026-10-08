package com.example.monthlyexpense.notification.work

import com.example.monthlyexpense.notification.ReplayBudget
import com.example.monthlyexpense.notification.ReplayBudgetState
import org.junit.Assert.*
import org.junit.Test

class AutomaticReplayBudgetTest {
    @Test fun resumedSessionRetainsTighterReadAndRecoveryLimits() {
        val session = ReplayBudget(ReplayBudgetState(reads = 2))
        val operation = ReplayBudget()
        val budget = combineAutomaticReplayBudgets(session, operation)
        assertTrue(budget.reserveRead())
        assertFalse(budget.reserveRead())
        assertTrue(budget.reserveRecovery())
        assertTrue(budget.reserveRead())
        assertFalse(budget.reserveRead())
        assertFalse(budget.reserveRecovery())
        assertEquals(4, session.state.reads)
        assertEquals(2, operation.state.reads)
        assertTrue(operation.state.recoveryUsed)
    }

    @Test fun closedSessionOrReceiptPersistenceFailureDoesNotAuthorizeAnAction() {
        val closedSession = ReplayBudget(save = { false })
        val operation = ReplayBudget()
        assertFalse(combineAutomaticReplayBudgets(closedSession, operation).reserveRead())
        assertEquals(0, operation.state.reads)
        val liveSession = ReplayBudget()
        val unavailableOperation = ReplayBudget(save = { false })
        assertFalse(combineAutomaticReplayBudgets(liveSession, unavailableOperation).reserveRecovery())
        assertTrue(liveSession.state.recoveryUsed)
        assertFalse(unavailableOperation.state.recoveryUsed)
    }
}
