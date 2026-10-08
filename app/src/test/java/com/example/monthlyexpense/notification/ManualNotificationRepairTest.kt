package com.example.monthlyexpense.notification

import org.junit.Assert.*
import org.junit.Test

class ManualNotificationRepairTest {
    @Test fun repairRequiresFreshConnectionEvenWhenOldFlagSaysConnected() {
        val pending = ArrayDeque<() -> Unit>()
        var unbinds = 0
        var rebinds = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { true },
            requestRebind = { rebinds++ }, requestUnbind = { unbinds++ },
            scheduleDelayed = { _, task -> pending.add(task) }
        )
        assertTrue(recovery.onManualRepairRequested())
        assertEquals(1, unbinds)
        assertEquals(NotificationListenerConnectionState.RECOVERING, recovery.connectionState)
        assertFalse(recovery.onManualRepairRequested())
        pending.removeFirst().invoke()
        assertEquals(1, rebinds)
        assertEquals(NotificationListenerConnectionState.RECOVERING, recovery.connectionState)
        recovery.onListenerConnected()
        pending.removeFirst().invoke()
        assertEquals(1, rebinds)
        assertEquals(NotificationListenerConnectionState.CONNECTED, recovery.connectionState)
    }

    @Test fun repairWithoutAuthorizationDoesNotUnbind() {
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { false }, isListenerConnected = { true },
            requestRebind = { error("must not bind") }, requestUnbind = { error("must not unbind") }
        )
        assertFalse(recovery.onManualRepairRequested())
    }

    @Test fun disablingAutomaticBookkeepingStopsOutstandingBackgroundRetries() {
        val pending = ArrayDeque<() -> Unit>()
        var enabled = true
        var requests = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { false }, requestRebind = { requests++ },
            scheduleDelayed = { _, task -> pending.add(task) }, isRecoveryEnabled = { enabled }
        )
        recovery.onBackgroundCheck()
        enabled = false
        pending.removeFirst().invoke()
        assertEquals(1, requests)
        assertTrue(pending.isEmpty())
        assertEquals(NotificationListenerConnectionState.DISCONNECTED, recovery.connectionState)
        assertFalse(recovery.onBackgroundCheck())
    }
}
