package com.example.monthlyexpense.notification.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.work.*
import com.example.monthlyexpense.notification.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Holds the preference listener strongly for the lifetime of the process. */
object NotificationRecoveryWork {
    const val PERIODIC_NAME = "notification_listener_health"
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(AutoBookkeepingSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferenceListener == null) {
            preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> synchronize(app) }
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        synchronize(app)
    }

    fun synchronize(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (AutoBookkeepingSettings(context).isEnabled) {
            manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotificationRecoveryWorker>(15, TimeUnit.MINUTES).build())
        } else {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork("notification_listener_startup")
            manager.cancelUniqueWork(NotificationAutoReplayWorker.WORK_NAME)
        }
    }

    fun afterSystemRestart(context: Context) {
        synchronize(context)
        if (!AutoBookkeepingSettings(context).isEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork("notification_listener_startup", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NotificationRecoveryWorker>().build())
    }
}

class NotificationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NotificationRecoveryWork.afterSystemRestart(context)
        }
    }
}

class NotificationRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val intake = (applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container.notificationIntake
        intake.initialize()
        intake.schedulePending()
        withContext(Dispatchers.IO) { enqueueStagedEvents(applicationContext) }
        if (!AutoBookkeepingSettings(applicationContext).isEnabled ||
            !NotificationAccessChecker.isGranted(applicationContext)) return Result.success()
        val recovery = NotificationListenerRecovery.forContext(applicationContext)
        val runtime = (applicationContext as com.example.monthlyexpense.MonthlyExpenseApplication).container.notifications
        runtime.health.check(background = true)
        // Keep this bounded worker alive while the existing retry coordinator awaits callbacks.
        withTimeoutOrNull(NotificationListenerRecovery.WORKER_WAIT_TIMEOUT_MS) {
            while (recovery.connectionState == NotificationListenerConnectionState.RECOVERING &&
                AutoBookkeepingSettings(applicationContext).isEnabled) delay(500L)
        }
        NotificationPipelineLog.event("background_check_completed state=${recovery.connectionState}")
        runtime.requestAutomaticReplay()
        // The next periodic check is the fallback; never create an unbounded worker retry loop.
        return Result.success()
    }
}
