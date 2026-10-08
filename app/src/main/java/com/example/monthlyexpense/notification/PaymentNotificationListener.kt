package com.example.monthlyexpense.notification

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.os.Build
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.example.monthlyexpense.notification.parser.PaymentSource
import com.example.monthlyexpense.notification.work.PaymentImportScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentNotificationListener : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AutoBookkeepingSettings
    private lateinit var listenerRecovery: NotificationListenerRecovery
    private lateinit var paymentImportScheduler: PaymentImportScheduler
    private val recentReplayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REPLAY_RECENT_NOTIFICATIONS && isListenerConnected) {
                replayRecentActiveNotifications()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AutoBookkeepingSettings(applicationContext)
        listenerRecovery = NotificationListenerRecovery.forContext(applicationContext)
        paymentImportScheduler = PaymentImportScheduler(applicationContext)
        (application as com.example.monthlyexpense.MonthlyExpenseApplication).container.notificationIntake.onAvailable()
        ContextCompat.registerReceiver(
            this,
            recentReplayReceiver,
            IntentFilter(ACTION_REPLAY_RECENT_NOTIFICATIONS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!listenerRecovery.onListenerConnected()) return
        connectionEpoch++
        connectedInstance = this
        isListenerConnected = true
        sendConnectionStateChanged()
        (application as com.example.monthlyexpense.MonthlyExpenseApplication).container.notificationIntake.onAvailable()
        serviceScope.launch {
            try { com.example.monthlyexpense.notification.work.enqueueStagedEvents(applicationContext) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RuntimeException) { NotificationPipelineLog.event("staged_recovery_failed type=${error.javaClass.simpleName}") }
        }
        replayRecentActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (connectedInstance != null && connectedInstance !== this) return
        connectedInstance = null
        isListenerConnected = false
        connectionEpoch++
        super.onListenerDisconnected()
        NotificationPipelineLog.event("listener_disconnected")
        sendConnectionStateChanged()
        listenerRecovery.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!PaymentPackages.isSupported(sbn.packageName)) return
        NotificationPipelineLog.event("notification_received")

        val notification = sbn.toRawNotification()
        NotificationMetrics.increment(PipelineMetric.RECEIVED)
        val source = sbn.packageName.toPaymentSource()

        (application as com.example.monthlyexpense.MonthlyExpenseApplication).container.notificationIntake.offer(notification)
    }

    override fun onDestroy() {
        if (connectedInstance == null || connectedInstance === this) {
            connectionEpoch++
            connectedInstance = null
            isListenerConnected = false
            NotificationPipelineLog.event("listener_destroyed")
            listenerRecovery.onListenerDisconnected()
            sendConnectionStateChanged()
        }
        unregisterReceiver(recentReplayReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun replayRecentActiveNotifications() {
        (application as com.example.monthlyexpense.MonthlyExpenseApplication).container.notifications.requestAutomaticReplay()
    }

    private fun StatusBarNotification.toRawNotification(): RawNotification {
        val extras = notification.extras
        return RawNotification(
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.map(CharSequence::toString)
                .orEmpty(),
            postTime = postTime,
            notificationKey = key.orEmpty(),
            channelId = notification.channelId,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            groupKey = groupKey
        )
    }

    private fun String.toPaymentSource(): PaymentSource = when (this) {
        PaymentPackages.WECHAT -> PaymentSource.WECHAT
        PaymentPackages.ALIPAY -> PaymentSource.ALIPAY
        else -> error("Unsupported payment package")
    }

    private fun sendConnectionStateChanged() {
        sendBroadcast(Intent(ACTION_LISTENER_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        @Volatile internal var connectionEpoch = 0L
            private set
        private var connectedInstance: PaymentNotificationListener? = null

        /** Capture only on main; the immutable handle performs Binder work on the reader's thread. */
        internal fun snapshotConnection(): SnapshotConnection? {
            val service = connectedInstance ?: return null
            if (!isListenerConnected) return null
            return SnapshotConnection(connectionEpoch) {
                service.activeNotifications?.filter { PaymentPackages.isSupported(it.packageName) }
                    ?.map { with(service) { it.toRawNotification() } }
            }
        }

        internal fun invalidateConnectionForReset() {
            connectionEpoch++
            isListenerConnected = false
            connectedInstance = null
        }

        internal fun unbindForRepair(context: Context) {
            connectionEpoch++
            val service = connectedInstance
            isListenerConnected = false
            if (service != null) {
                service.requestUnbind()
            } else if (Build.VERSION.SDK_INT >= 34) {
                requestUnbind(ComponentName(context, PaymentNotificationListener::class.java))
            }
        }

        const val ACTION_EXPENSE_ADDED = "com.example.monthlyexpense.EXPENSE_ADDED"
        const val ACTION_LISTENER_STATE_CHANGED =
            "com.example.monthlyexpense.LISTENER_STATE_CHANGED"
        const val ACTION_REPLAY_RECENT_NOTIFICATIONS =
            "com.example.monthlyexpense.REPLAY_RECENT_NOTIFICATIONS"
        @Volatile
        var isListenerConnected: Boolean = false
            private set
    }
}
