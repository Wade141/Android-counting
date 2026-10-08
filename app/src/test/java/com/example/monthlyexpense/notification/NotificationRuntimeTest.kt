package com.example.monthlyexpense.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.monthlyexpense.MonthlyExpenseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationRuntimeTest {
    @Test fun missingServiceCanRebindEvenWhileHealthRepairIsCoolingDown() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
        // Robolectric can reuse the classloader with a new Application. A real process cannot.
        ReflectionHelpers.setStaticField(NotificationListenerRecovery::class.java, "processRecovery", null)
        val app: MonthlyExpenseApplication = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        app.container.autoSettings.isEnabled = true
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, PaymentNotificationListener::class.java), true)
        PaymentNotificationListener.invalidateConnectionForReset()
        val prefs = app.getSharedPreferences("notification_health_repair", Context.MODE_PRIVATE)
        val recent = System.currentTimeMillis()
        prefs.edit().putLong("last_accepted", recent).commit()
        assertTrue(app.container.notifications.requestRecovery(PaymentNotificationListener.connectionEpoch, false))
        assertEquals(NotificationListenerConnectionState.RECOVERING,
            NotificationListenerRecovery.forContext(app).connectionState)
        assertEquals(recent, prefs.getLong("last_accepted", 0))
        app.container.autoSettings.isEnabled = false
        ReflectionHelpers.getField<CoroutineScope>(app.container.notifications, "scope")
            .coroutineContext[Job]!!.cancelAndJoin()
        WorkManagerTestInitHelper.closeWorkDatabase()
        } finally {
            ReflectionHelpers.setStaticField(NotificationListenerRecovery::class.java, "processRecovery", null)
            Dispatchers.resetMain()
        }
    }
}
