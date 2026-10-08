package com.example.monthlyexpense.refresh

import com.example.monthlyexpense.notification.NotificationListenerConnectionState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRequestMergerTest {
    @Test
    fun idleOfferClaimsDrainOwnershipAndReturnsTheEntry() {
        val merger = RefreshRequestMerger()
        val entry = queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED)

        assertEquals(entry, merger.offer(entry))
        assertNull(merger.nextOrFinish())
        assertEquals(entry, merger.offer(entry))
    }

    @Test
    fun oneHundredOverlappingOffersBecomeOneTrailingRequest() {
        val merger = RefreshRequestMerger()
        val first = queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED)
        assertEquals(first, merger.offer(first))

        repeat(100) {
            merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.DISCONNECTED))
        }

        val trailing = requireNotNull(merger.nextOrFinish())
        assertEquals(RefreshScope.HOME, trailing.request.scope)
        assertEquals(NotificationListenerConnectionState.DISCONNECTED, trailing.request.notificationState)
        assertNull(merger.nextOrFinish())
    }

    @Test
    fun pendingMergePromotesHomeScopeToAll() {
        val merger = RefreshRequestMerger()
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED))
        merger.offer(queued(RefreshScope.ALL, NotificationListenerConnectionState.DISCONNECTED))

        assertEquals(RefreshScope.ALL, requireNotNull(merger.nextOrFinish()).request.scope)
    }

    @Test
    fun pendingMergeRetainsLatestNotificationState() {
        val merger = RefreshRequestMerger()
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED))
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.DISCONNECTED))
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.CONNECTED))

        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            requireNotNull(merger.nextOrFinish()).request.notificationState
        )
    }

    @Test
    fun pendingMergeKeepsExternalNotificationSampleWhenNewerSyntheticRequestArrives() {
        val merger = RefreshRequestMerger()
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED))
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.CONNECTED))
        merger.offer(
            queued(
                scope = RefreshScope.HOME,
                notificationState = NotificationListenerConnectionState.NOT_AUTHORIZED,
                notificationStateIsExternal = false
            )
        )

        val trailing = requireNotNull(merger.nextOrFinish())
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            trailing.request.notificationState
        )
        assertTrue(trailing.notificationStateIsExternal)
    }

    @Test
    fun pendingMergeConcatenatesWaitersInArrivalOrder() {
        val merger = RefreshRequestMerger()
        val firstWaiter = CompletableDeferred<RefreshResult>()
        val secondWaiter = CompletableDeferred<RefreshResult>()
        val thirdWaiter = CompletableDeferred<RefreshResult>()
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED))
        merger.offer(
            queued(
                RefreshScope.HOME,
                NotificationListenerConnectionState.DISCONNECTED,
                waiters = listOf(firstWaiter, secondWaiter)
            )
        )
        merger.offer(
            queued(
                RefreshScope.ALL,
                NotificationListenerConnectionState.CONNECTED,
                waiters = listOf(thirdWaiter)
            )
        )

        assertEquals(
            listOf(firstWaiter, secondWaiter, thirdWaiter),
            requireNotNull(merger.nextOrFinish()).waiters
        )
    }

    @Test
    fun pendingMergeOrsPageFailureReporting() {
        val merger = RefreshRequestMerger()
        merger.offer(queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED))
        merger.offer(
            queued(
                RefreshScope.HOME,
                NotificationListenerConnectionState.DISCONNECTED,
                reportPageFailure = false
            )
        )
        merger.offer(
            queued(
                RefreshScope.HOME,
                NotificationListenerConnectionState.CONNECTED,
                reportPageFailure = true
            )
        )
        merger.offer(
            queued(
                RefreshScope.HOME,
                NotificationListenerConnectionState.CONNECTED,
                reportPageFailure = false
            )
        )

        assertTrue(requireNotNull(merger.nextOrFinish()).reportPageFailure)
    }

    @Test
    fun nextOrFinishKeepsDrainOwnershipForRequestsArrivingDuringTrailingRead() {
        val merger = RefreshRequestMerger()
        val first = queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED)
        val second = queued(RefreshScope.HOME, NotificationListenerConnectionState.DISCONNECTED)
        val third = queued(RefreshScope.ALL, NotificationListenerConnectionState.CONNECTED)
        assertEquals(first, merger.offer(first))
        assertNull(merger.offer(second))

        assertEquals(second, merger.nextOrFinish())
        assertNull(merger.offer(third))
        assertEquals(third, merger.nextOrFinish())
        assertNull(merger.nextOrFinish())
    }

    @Test
    fun invalidatePendingReturnsAndClearsOnlyThePendingEntry() {
        val merger = RefreshRequestMerger()
        val active = queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED)
        val pending = queued(RefreshScope.ALL, NotificationListenerConnectionState.CONNECTED)
        assertEquals(active, merger.offer(active))
        assertNull(merger.offer(pending))

        assertEquals(pending, merger.invalidatePending())
        assertNull(merger.nextOrFinish())
        assertEquals(active, merger.offer(active))
    }

    @Test
    fun cancelDrainReturnsPendingEntryAndReleasesDrainOwnership() {
        val merger = RefreshRequestMerger()
        val active = queued(RefreshScope.HOME, NotificationListenerConnectionState.NOT_AUTHORIZED)
        val pending = queued(RefreshScope.ALL, NotificationListenerConnectionState.CONNECTED)
        val replacement = queued(RefreshScope.HOME, NotificationListenerConnectionState.DISCONNECTED)
        assertEquals(active, merger.offer(active))
        assertNull(merger.offer(pending))

        assertEquals(pending, merger.cancelDrain())
        assertEquals(replacement, merger.offer(replacement))
    }

    private fun queued(
        scope: RefreshScope,
        notificationState: NotificationListenerConnectionState,
        waiters: List<CompletableDeferred<RefreshResult>> = emptyList(),
        reportPageFailure: Boolean = false,
        notificationStateIsExternal: Boolean = true
    ) = QueuedRefresh(
        request = RefreshRequest(scope, notificationState),
        waiters = waiters,
        reportPageFailure = reportPageFailure,
        notificationStateIsExternal = notificationStateIsExternal
    )
}
