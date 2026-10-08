package com.example.monthlyexpense.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationListenerConnectionStateTest {
    @Test
    fun resolvesAuthorizationAndLiveConnectionIndependently() {
        assertEquals(
            NotificationListenerConnectionState.NOT_AUTHORIZED,
            NotificationListenerConnectionState.resolve(accessGranted = false, listenerConnected = false)
        )
        assertEquals(
            NotificationListenerConnectionState.DISCONNECTED,
            NotificationListenerConnectionState.resolve(accessGranted = true, listenerConnected = false)
        )
        assertEquals(
            NotificationListenerConnectionState.CONNECTED,
            NotificationListenerConnectionState.resolve(accessGranted = true, listenerConnected = true)
        )
    }
}
