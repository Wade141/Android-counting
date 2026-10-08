package com.example.monthlyexpense.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationRecoveryTimeoutTest {
    @Test
    fun retriesWithoutAnotherLifecycleEventAndEventuallyStops() {
        val pending = ArrayDeque<() -> Unit>()
        var requests = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { false },
            requestRebind = { requests++ }, scheduleDelayed = { _, task -> pending.add(task) }
        )
        recovery.onAppResumed()
        assertEquals("A rebind needs a completion timeout", 1, pending.size)
        repeat(4) { pending.removeFirst().invoke() }
        assertEquals(4, requests)
        assertTrue(pending.isEmpty())
        assertEquals(NotificationListenerConnectionState.RECOVERY_FAILED, recovery.connectionState)
        assertFalse(recovery.onListenerDisconnected())
        assertTrue(pending.isEmpty())
        assertTrue(recovery.onManualReplayRequested())
        assertEquals(5, requests)
        assertEquals(NotificationListenerConnectionState.RECOVERING, recovery.connectionState)
    }

    @Test
    fun connectionCancelsOldTimeoutEvenAfterAnotherDisconnection() {
        val pending = ArrayDeque<() -> Unit>()
        var connected = false
        var requests = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { connected },
            requestRebind = { requests++ }, scheduleDelayed = { _, task -> pending.add(task) }
        )
        recovery.onAppResumed()
        connected = true
        recovery.onListenerConnected()
        assertEquals(NotificationListenerConnectionState.CONNECTED, recovery.connectionState)
        connected = false
        recovery.onListenerDisconnected()
        pending.removeFirst().invoke()
        assertEquals(2, requests)
        assertEquals(1, pending.size)
        pending.removeFirst().invoke()
        assertEquals(3, requests)
    }

    @Test
    fun permissionRevocationStopsRetryAndUpdatesVisibleState() {
        val pending = ArrayDeque<() -> Unit>()
        var granted = true
        var requests = 0
        var changes = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { granted }, isListenerConnected = { false },
            requestRebind = { requests++ }, scheduleDelayed = { _, task -> pending.add(task) },
            onStateChanged = { changes++ }
        )
        recovery.onAppResumed()
        granted = false
        pending.removeFirst().invoke()
        assertEquals(1, requests)
        assertTrue(pending.isEmpty())
        assertEquals(2, changes)
        assertEquals(NotificationListenerConnectionState.NOT_AUTHORIZED, recovery.connectionState)
    }

    @Test
    fun repeatedResumesDoNotMultiplyRetriesAndExceptionsStillReachFailure() {
        val pending = ArrayDeque<() -> Unit>()
        val states = mutableListOf<NotificationListenerConnectionState>()
        val logs = mutableListOf<String>()
        var requests = 0
        lateinit var recovery: NotificationListenerRecovery
        recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { false },
            requestRebind = { requests++; throw SecurityException("private data") },
            scheduleDelayed = { _, task -> pending.add(task) },
            onStateChanged = { states.add(recovery.connectionState) }, log = { logs.add(it) }
        )
        recovery.onAppResumed()
        repeat(10) { assertFalse(recovery.onAppResumed()) }
        assertEquals(1, pending.size)
        repeat(4) { pending.removeFirst().invoke() }
        assertEquals(4, requests)
        assertTrue(pending.isEmpty())
        assertEquals(listOf(NotificationListenerConnectionState.RECOVERING,
            NotificationListenerConnectionState.RECOVERY_FAILED), states)
        assertFalse(logs.any { it.contains("private data") })
    }
}
