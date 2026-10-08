package com.example.monthlyexpense.notification

import com.example.monthlyexpense.notification.intake.*
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.replay.ReplayTerminationReason
import org.junit.Assert.*
import org.junit.Test

class NotificationReplaySessionRunnerTest {
    @Test fun newBookStopsEvenAnEmptyReplayBeforeItCanComplete() {
        assertEquals(ReplayTerminationReason.BOOK_CHANGED,
            ReplaySessionPolicy.admissionFailure("old", "new", true, true, 100, false))
    }

    @Test fun pendingRestoreDoesNotPermanentlyInvalidateAnUnchangedBook() {
        assertEquals(ReplayTerminationReason.INTERRUPTED,
            ReplaySessionPolicy.admissionFailure("book", "book", true, true, 100, true))
    }

    @Test fun deadlinePermissionAndGlobalDisableAreCheckedWithoutMembers() {
        assertEquals(ReplayTerminationReason.TIMEOUT,
            ReplaySessionPolicy.admissionFailure("book", "book", true, true, 0, false))
        assertEquals(ReplayTerminationReason.PERMISSION,
            ReplaySessionPolicy.admissionFailure("book", "book", true, false, 100, false))
        assertEquals(ReplayTerminationReason.DISABLED,
            ReplaySessionPolicy.admissionFailure("book", "book", false, true, 100, false))
        assertNull(ReplaySessionPolicy.admissionFailure("book", "book", true, true, 100, false))
    }

    @Test fun failedBoundaryDoesNotCountAsAnEmptySuccessfulSnapshot() {
        val arming = policy(SourcePolicyState.ARMING).copy(failure = "CAPACITY")
        assertEquals(ReplayTerminationReason.CAPACITY,
            ReplaySessionPolicy.boundaryFailure(SourcePolicySnapshot(true, true, mapOf(PaymentSource.WECHAT to arming))))
        assertNull(ReplaySessionPolicy.boundaryFailure(SourcePolicySnapshot(true, true,
            mapOf(PaymentSource.WECHAT to policy(SourcePolicyState.ON)))))
    }

    @Test fun oldHandedOffHistoryIsNotAddedToANewBookButQuarantineRemainsVisible() {
        assertFalse(ReplaySessionPolicy.includeLocal(record(IntakeState.HANDED_OFF, "old"), "new"))
        assertFalse(ReplaySessionPolicy.includeLocal(record(IntakeState.HANDED_OFF, "new"), "new"))
        assertFalse(ReplaySessionPolicy.includeLocal(record(IntakeState.PENDING, "old"), "new"))
        assertTrue(ReplaySessionPolicy.includeLocal(record(IntakeState.QUARANTINED, "old"), "new"))
        assertTrue(ReplaySessionPolicy.includeLocal(record(IntakeState.PENDING, "new"), "new"))
        assertFalse(ReplaySessionPolicy.includeLocal(record(IntakeState.EXPIRED, "new"), "new"))
    }

    private fun policy(state: SourcePolicyState) = SourcePolicy(PaymentSource.WECHAT, true, 3,
        state, IntakeClock(10, 10, 1))
    private fun record(state: IntakeState, book: String) = IntakeRecord("id", "session", 1, book, 3,
        PaymentSource.WECHAT, 10, 10, 1, state, 0, 0, null, null)
}
