package com.example.monthlyexpense.notification

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NotificationComponentResetTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("reset_test", Context.MODE_PRIVATE)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun cooldownAndHourlyLimitSurviveNewInstances() {
        var now = 10_000_000L
        var enabled = true
        fun resetter() = NotificationComponentReset(prefs, { now }, { enabled = it }, { _, task -> task() })
        assertTrue(resetter().resetImmediately())
        assertTrue(enabled)
        assertFalse(resetter().resetImmediately())
        repeat(2) { now += 300_000L; assertTrue(resetter().resetImmediately()) }
        now += 300_000L
        assertFalse(resetter().resetImmediately())
        now += 3_600_000L
        assertTrue(resetter().resetImmediately())
    }

    @Test fun disableFailureStillRestoresEnabledComponent() {
        var enabled = true
        val resetter = NotificationComponentReset(prefs, { 10_000_000L }, {
            enabled = it
            if (!it) throw IllegalStateException("private")
        }, { _, task -> task() })
        assertFalse(resetter.resetImmediately())
        assertTrue(enabled)
        assertFalse(prefs.getBoolean("pending_enable", false))
    }

    @Test fun failedEnableIsRepairedAfterProcessRestartDespiteCooldown() {
        var enabled = true
        val broken = NotificationComponentReset(prefs, { 10_000_000L }, {
            if (it) throw IllegalStateException("private")
            enabled = false
        }, { _, task -> task() })
        assertFalse(broken.resetImmediately())
        assertFalse(enabled)
        assertTrue(prefs.getBoolean("pending_enable", false))
        val restarted = NotificationComponentReset(prefs, { 10_000_001L }, { enabled = it })
        assertTrue(restarted.restorePendingEnable())
        assertTrue(enabled)
        assertFalse(restarted.resetImmediately())
    }

    @Test @Config(sdk = [26]) fun factoryRestoresDisabledServiceWithPendingJournal() {
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        val actual = context.getSharedPreferences("notification_component_reset", Context.MODE_PRIVATE)
        actual.edit().clear().putBoolean("pending_enable", true).commit()
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertTrue(NotificationComponentReset.forContext(context).restorePendingEnable())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component))
        assertFalse(actual.getBoolean("pending_enable", false))
        var completed: Boolean? = null
        NotificationComponentReset.forContext(context).tryReset { completed = it }
        assertNull(completed)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(component))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NotificationComponentReset.DISABLED_HOLD_MS))
        assertEquals(true, completed)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component))
    }

    @Test fun modernAndroidRepairNeverDisablesServiceComponent() {
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        val actual = context.getSharedPreferences("notification_component_reset", Context.MODE_PRIVATE)
        actual.edit().clear().commit()
        val initial = context.packageManager.getComponentEnabledSetting(component)
        var completed: Boolean? = null
        NotificationComponentReset.forContext(context).tryReset { completed = it }
        assertNull(completed)
        assertEquals(initial, context.packageManager.getComponentEnabledSetting(component))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(true, completed)
        assertEquals(initial, context.packageManager.getComponentEnabledSetting(component))
    }

    private fun NotificationComponentReset.resetImmediately(): Boolean {
        var completed: Boolean? = null
        tryReset { completed = it }
        return requireNotNull(completed)
    }

    @Test fun scheduledRestoreCannotBeShortenedByAnotherRecoveryRequest() {
        var enabled = true
        var completed: Boolean? = null
        var restore: (() -> Unit)? = null
        val resetter = NotificationComponentReset(prefs, { 10_000_000L }, { enabled = it }, { delay, task ->
            assertTrue(delay > 10_000L)
            restore = task
        })
        resetter.tryReset { completed = it }
        try {
            assertFalse(enabled)
            assertNull(completed)
            assertFalse(resetter.restorePendingEnable())
            assertFalse(resetter.resetImmediately())
            assertFalse(enabled)
        } finally { restore!!.invoke() }
        assertTrue(enabled)
        assertEquals(true, completed)
    }
}
