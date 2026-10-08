package com.example.monthlyexpense.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

class NotificationListenerRecovery(
    private val isAccessGranted: () -> Boolean,
    private val isListenerConnected: () -> Boolean,
    private val requestRebind: () -> Unit,
    private val requestRecentReplay: () -> Unit = {},
    private val scheduleDelayed: (Long, () -> Unit) -> Unit = { delay, retry ->
        Handler(Looper.getMainLooper()).postDelayed({ retry() }, delay)
    },
    private val onStateChanged: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val requestUnbind: () -> Unit = {},
    private val isRecoveryEnabled: () -> Boolean = { true },
    private val resetComponent: ((Boolean) -> Unit) -> Unit = { it(false) },
    private val connectionEpoch: () -> Long = { 0L }
) {
    private var generation = 0L
    private var attempts = 0
    private var phase = NotificationListenerConnectionState.DISCONNECTED
    private var awaitingFreshConnection = false
    private var componentResetInProgress = false

    val connectionState: NotificationListenerConnectionState
        @Synchronized get() = when {
            !isAccessGranted() -> NotificationListenerConnectionState.NOT_AUTHORIZED
            awaitingFreshConnection -> phase
            isListenerConnected() -> NotificationListenerConnectionState.CONNECTED
            else -> phase
        }

    fun onAppResumed(): Boolean = startRecovery(restartFailed = true)

    fun onBackgroundCheck(): Boolean = startRecovery(restartFailed = true)

    @Synchronized
    fun onManualRepairRequested(): Boolean {
        return beginRepair("manual_repair_requested")
    }

    @Synchronized
    fun onHealthCheckFailed(expectedEpoch: Long): Boolean {
        if (connectionEpoch() != expectedEpoch) return false
        return beginRepair("health_repair_requested")
    }

    @Synchronized
    fun onHealthUnavailable(expectedEpoch: Long) {
        if (connectionEpoch() != expectedEpoch || phase == NotificationListenerConnectionState.RECOVERING) return
        awaitingFreshConnection = true
        setPhase(NotificationListenerConnectionState.RECOVERY_FAILED)
    }

    private fun beginRepair(reason: String): Boolean {
        if (!isRecoveryEnabled() || !isAccessGranted() || phase == NotificationListenerConnectionState.RECOVERING) return false
        awaitingFreshConnection = true
        generation++
        val token = generation
        attempts = 0
        setPhase(NotificationListenerConnectionState.RECOVERING)
        log(reason)
        try {
            requestUnbind()
        } catch (error: RuntimeException) {
            log("unbind_error type=${error.javaClass.simpleName}")
        }
        // Let the system finish unbinding before requesting a fresh connection.
        scheduleDelayed(1_000L) {
            synchronized(this) {
                if (token == generation && !finishIfUnavailableOrConnected()) attempt(token)
            }
        }
        return true
    }

    fun onListenerDisconnected(): Boolean = startRecovery(restartFailed = false)

    @Synchronized
    fun onListenerConnected(): Boolean {
        if (componentResetInProgress) {
            log("listener_callback_ignored reason=component_disabled")
            return false
        }
        awaitingFreshConnection = false
        generation++ // Invalidate pending timeouts, including those from an older connection.
        setPhase(NotificationListenerConnectionState.CONNECTED)
        log("listener_connected")
        return true
    }

    fun onManualReplayRequested(): Boolean {
        if (!isAccessGranted()) return false
        if (isListenerConnected()) {
            requestRecentReplay()
            return true
        }
        startRecovery(restartFailed = true)
        return true
    }

    @Synchronized
    private fun startRecovery(restartFailed: Boolean): Boolean {
        if (finishIfUnavailableOrConnected()) return false
        if (phase == NotificationListenerConnectionState.RECOVERING) return false
        if (phase == NotificationListenerConnectionState.RECOVERY_FAILED && !restartFailed) return false
        generation++
        attempts = 0
        setPhase(NotificationListenerConnectionState.RECOVERING)
        attempt(generation)
        return true
    }

    private fun attempt(token: Long) {
        attempts++
        log("rebind_requested attempt=$attempts")
        try {
            requestRebind()
        } catch (error: RuntimeException) {
            // Exception messages may contain notification data; only log the error type.
            log("rebind_error type=${error.javaClass.simpleName}")
        }
        if (token != generation || finishIfUnavailableOrConnected()) return
        scheduleDelayed(ATTEMPT_TIMEOUTS[attempts - 1]) { onTimeout(token) }
    }

    @Synchronized
    private fun onTimeout(token: Long) {
        if (token != generation || finishIfUnavailableOrConnected()) return
        log("rebind_timeout attempt=$attempts")
        if (attempts >= ATTEMPT_TIMEOUTS.size) {
            escalateToComponentReset(token)
        } else {
            attempt(token)
        }
    }

    private fun escalateToComponentReset(token: Long) {
        awaitingFreshConnection = true
        componentResetInProgress = true
        try {
            resetComponent { reset ->
                synchronized(this) { onComponentResetFinished(token, reset) }
            }
        } catch (error: RuntimeException) {
            componentResetInProgress = false
            log("component_reset_error type=${error.javaClass.simpleName}")
            if (token == generation) failRecovery()
        }
    }

    private fun onComponentResetFinished(token: Long, reset: Boolean) {
        componentResetInProgress = false
        if (token != generation || finishIfUnavailableOrConnected()) return
        if (!reset) {
            failRecovery()
            return
        }
        log("component_reset_awaiting_connection")
        // Package-change handling can be asynchronous. Give it time before requesting a bind.
        scheduleDelayed(1_000L) {
            synchronized(this) {
                if (token != generation || finishIfUnavailableOrConnected()) return@synchronized
                try { requestRebind() } catch (error: RuntimeException) {
                    log("rebind_error type=${error.javaClass.simpleName}")
                }
                if (token != generation || finishIfUnavailableOrConnected()) return@synchronized
                scheduleDelayed(20_000L) {
                    synchronized(this) {
                        if (token == generation && !finishIfUnavailableOrConnected()) failRecovery()
                    }
                }
            }
        }
    }

    private fun failRecovery() {
        generation++
        setPhase(NotificationListenerConnectionState.RECOVERY_FAILED)
        log("recovery_failed")
    }

    private fun finishIfUnavailableOrConnected(): Boolean {
        val state = when {
            !isAccessGranted() -> NotificationListenerConnectionState.NOT_AUTHORIZED
            !awaitingFreshConnection && isListenerConnected() -> NotificationListenerConnectionState.CONNECTED
            !isRecoveryEnabled() -> NotificationListenerConnectionState.DISCONNECTED
            else -> return false
        }
        generation++
        awaitingFreshConnection = false
        setPhase(state)
        return true
    }

    private fun setPhase(state: NotificationListenerConnectionState) {
        if (phase == state) return
        phase = state
        log("listener_state state=${state.name}")
        onStateChanged()
    }

    companion object {
        // 60s ordinary retries + 12s disabled + 1s settling + 20s callback (+ 1s manual unbind).
        const val WORKER_WAIT_TIMEOUT_MS = 110_000L
        @Volatile
        private var processRecovery: NotificationListenerRecovery? = null

        fun forContext(context: Context): NotificationListenerRecovery {
            val appContext = context.applicationContext
            NotificationComponentReset.forContext(appContext).restorePendingEnable()
            return processRecovery ?: synchronized(this) {
                processRecovery ?: NotificationListenerRecovery(
                    isAccessGranted = { NotificationAccessChecker.isGranted(appContext) },
                    connectionEpoch = { PaymentNotificationListener.connectionEpoch },
                    isListenerConnected = { PaymentNotificationListener.isListenerConnected },
                    requestRebind = {
                        NotificationListenerService.requestRebind(
                            ComponentName(appContext, PaymentNotificationListener::class.java)
                        )
                    },
                    onStateChanged = {
                        appContext.sendBroadcast(
                            Intent(PaymentNotificationListener.ACTION_LISTENER_STATE_CHANGED)
                                .setPackage(appContext.packageName)
                        )
                    },
                    log = { NotificationPipelineLog.event(it) },
                    requestUnbind = { PaymentNotificationListener.unbindForRepair(appContext) },
                    resetComponent = { completed ->
                        PaymentNotificationListener.invalidateConnectionForReset()
                        NotificationComponentReset.forContext(appContext).tryReset(completed)
                    },
                    isRecoveryEnabled = { AutoBookkeepingSettings(appContext).isEnabled },
                    requestRecentReplay = {
                        appContext.sendBroadcast(
                            Intent(PaymentNotificationListener.ACTION_REPLAY_RECENT_NOTIFICATIONS)
                                .setPackage(appContext.packageName)
                        )
                    }
                ).also { processRecovery = it }
            }
        }

        // Four requests, each with a completion timeout; at most 60 seconds of scheduled waits.
        private val ATTEMPT_TIMEOUTS = longArrayOf(10_000L, 10_000L, 20_000L, 20_000L)
    }
}
