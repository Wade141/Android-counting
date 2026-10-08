package com.example.monthlyexpense.notification

enum class NotificationListenerConnectionState {
    NOT_AUTHORIZED,
    DISCONNECTED,
    RECOVERING,
    RECOVERY_FAILED,
    CONNECTED;

    companion object {
        fun resolve(
            accessGranted: Boolean,
            listenerConnected: Boolean
        ): NotificationListenerConnectionState = when {
            !accessGranted -> NOT_AUTHORIZED
            listenerConnected -> CONNECTED
            else -> DISCONNECTED
        }
    }
}
