package com.example.monthlyexpense.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationComponentRecoveryTest {
    @Test fun callbackWhileComponentIsStillDisabledCannotReportSuccess() {
        val pending = ArrayDeque<() -> Unit>()
        var finishReset: ((Boolean) -> Unit)? = null
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { false }, requestRebind = {},
            scheduleDelayed = { _, task -> pending.add(task) }, resetComponent = { finishReset = it }
        )
        recovery.onAppResumed()
        repeat(4) { pending.removeFirst()() }
        assertFalse(recovery.onListenerConnected())
        assertEquals(NotificationListenerConnectionState.RECOVERING, recovery.connectionState)
        finishReset!!(true)
        assertTrue(recovery.onListenerConnected())
        assertEquals(NotificationListenerConnectionState.CONNECTED, recovery.connectionState)
    }

    @Test fun disabledOrRevokedAccessPreventsEscalation() {
        for (revoke in listOf(false, true)) {
            val pending = ArrayDeque<() -> Unit>()
            var enabled = true
            var granted = true
            var resets = 0
            val recovery = NotificationListenerRecovery(
                isAccessGranted = { granted }, isListenerConnected = { false }, requestRebind = {},
                scheduleDelayed = { _, task -> pending.add(task) }, isRecoveryEnabled = { enabled },
                resetComponent = { resets++; it(true) }
            )
            recovery.onBackgroundCheck()
            repeat(3) { pending.removeFirst()() }
            if (revoke) granted = false else enabled = false
            pending.removeFirst()()
            assertEquals(0, resets)
            assertTrue(pending.isEmpty())
        }
    }

    @Test fun deniedOrThrowingResetDoesNotStartAnotherRetryLoop() {
        for (throws in listOf(false, true)) {
            val pending = ArrayDeque<() -> Unit>()
            val recovery = NotificationListenerRecovery(
                isAccessGranted = { true }, isListenerConnected = { false }, requestRebind = {},
                scheduleDelayed = { _, task -> pending.add(task) },
                resetComponent = { if (throws) throw IllegalStateException("private") else it(false) }
            )
            recovery.onAppResumed()
            repeat(4) { pending.removeFirst()() }
            assertEquals(NotificationListenerConnectionState.RECOVERY_FAILED, recovery.connectionState)
            assertTrue(pending.isEmpty())
        }
    }

    @Test fun automaticAndManualRecoveryEscalateOnceAndRequireFreshCallback() {
        for (manual in listOf(false, true)) {
            val pending = ArrayDeque<() -> Unit>()
            var resets = 0
            var connected = manual
            val recovery = NotificationListenerRecovery(
                isAccessGranted = { true }, isListenerConnected = { connected },
                requestRebind = {}, requestUnbind = { connected = false },
                scheduleDelayed = { _, task -> pending.add(task) },
                resetComponent = { resets++; connected = true; it(true) }
            )
            if (manual) { recovery.onManualRepairRequested(); pending.removeFirst()() }
            else recovery.onAppResumed()
            repeat(4) { pending.removeFirst()() }
            assertEquals(1, resets)
            assertEquals(NotificationListenerConnectionState.RECOVERING, recovery.connectionState)
            recovery.onListenerConnected()
            while (pending.isNotEmpty()) pending.removeFirst()()
            assertEquals(NotificationListenerConnectionState.CONNECTED, recovery.connectionState)
            assertEquals(1, resets)
        }
    }

    @Test fun resetWithoutCallbackEventuallyFailsAndStops() {
        val pending = ArrayDeque<() -> Unit>()
        var resets = 0
        val recovery = NotificationListenerRecovery(
            isAccessGranted = { true }, isListenerConnected = { false }, requestRebind = {},
            scheduleDelayed = { _, task -> pending.add(task) }, resetComponent = { resets++; it(true) }
        )
        recovery.onAppResumed()
        var callbacks = 0
        while (pending.isNotEmpty() && callbacks++ < 10) pending.removeFirst()()
        assertTrue(pending.isEmpty())
        assertEquals(1, resets)
        assertEquals(NotificationListenerConnectionState.RECOVERY_FAILED, recovery.connectionState)
    }
}
