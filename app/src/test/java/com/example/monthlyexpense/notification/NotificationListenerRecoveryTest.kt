package com.example.monthlyexpense.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerRecoveryTest {
    @Test
    fun requestsRebindWhenNotificationAccessIsGranted() {
        var requested = false
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true },
            isListenerConnected = { false },
            requestRebind = { requested = true },
            scheduleDelayed = { _, _ -> }
        )

        assertTrue(recovery.onAppResumed())
        assertTrue(requested)
    }

    @Test
    fun doesNotRequestRebindWithoutNotificationAccess() {
        var requested = false
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { false },
            isListenerConnected = { false },
            requestRebind = { requested = true },
            scheduleDelayed = { _, _ -> }
        )

        assertFalse(recovery.onAppResumed())
        assertFalse(requested)
    }


    @Test
    fun doesNotRequestRebindWhenListenerIsAlreadyConnected() {
        var requested = false
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true },
            isListenerConnected = { true },
            requestRebind = { requested = true },
            scheduleDelayed = { _, _ -> }
        )

        assertFalse(recovery.onAppResumed())
        assertFalse(requested)
    }

    @Test
    fun requestsRebindOnDisconnectAndThrottlesRapidRetries() {
        var requestCount = 0
        var scheduledDelay = -1L
        var scheduledRetry: (() -> Unit)? = null
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true },
            isListenerConnected = { false },
            requestRebind = { requestCount++ },
            scheduleDelayed = { delay, retry ->
                scheduledDelay = delay
                scheduledRetry = retry
            }
        )

        assertTrue(recovery.onListenerDisconnected())
        assertFalse(recovery.onListenerDisconnected())
        assertTrue(scheduledDelay == 10_000L)
        assertTrue(requestCount == 1)

        scheduledRetry!!.invoke()
        assertTrue(requestCount == 2)
    }

    @Test
    fun manualReplayScansImmediatelyWhenListenerIsConnected() {
        var rebindCount = 0
        var replayCount = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true },
            isListenerConnected = { true },
            requestRebind = { rebindCount++ },
            requestRecentReplay = { replayCount++ },
            scheduleDelayed = { _, _ -> }
        )

        assertTrue(recovery.onManualReplayRequested())
        assertTrue(rebindCount == 0)
        assertTrue(replayCount == 1)
    }

    @Test
    fun manualReplayRequestsRebindWhenListenerIsDisconnected() {
        var rebindCount = 0
        var replayCount = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true },
            isListenerConnected = { false },
            requestRebind = { rebindCount++ },
            requestRecentReplay = { replayCount++ },
            scheduleDelayed = { _, _ -> }
        )

        assertTrue(recovery.onManualReplayRequested())
        assertTrue(rebindCount == 1)
        assertTrue(replayCount == 0)
    }

    @Test
    fun manualReplayDoesNothingWithoutNotificationAccess() {
        var rebindCount = 0
        var replayCount = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { false },
            isListenerConnected = { false },
            requestRebind = { rebindCount++ },
            requestRecentReplay = { replayCount++ },
            scheduleDelayed = { _, _ -> }
        )

        assertFalse(recovery.onManualReplayRequested())
        assertTrue(rebindCount == 0)
        assertTrue(replayCount == 0)
    }
}
