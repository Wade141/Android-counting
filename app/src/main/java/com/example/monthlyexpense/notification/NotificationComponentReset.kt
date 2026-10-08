package com.example.monthlyexpense.notification

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.service.notification.NotificationListenerService

/** Persist the enable journal before disabling so process death cannot lose the repair intent. */
internal class NotificationComponentReset(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val setEnabled: (Boolean) -> Unit,
    private val scheduleRestore: (Long, () -> Unit) -> Unit = { delay, task ->
        check(Handler(Looper.getMainLooper()).postDelayed(task, delay))
    },
    private val disabledHoldMs: Long = DISABLED_HOLD_MS
) {
    fun restorePendingEnable(): Boolean = synchronized(lock) {
        if (!preferences.getBoolean("pending_enable", false)) return@synchronized true
        if (resetInProgress) return@synchronized false
        restoreEnabled()
    }

    fun tryReset(onComplete: (Boolean) -> Unit) = synchronized(lock) {
        if (!restorePendingEnable()) { onComplete(false); return@synchronized }
        val time = now()
        val last = preferences.getLong("last_reset", Long.MIN_VALUE)
        if (last != Long.MIN_VALUE && (time < last || time - last < COOLDOWN_MS)) {
            NotificationPipelineLog.event("component_reset_skipped reason=cooldown")
            onComplete(false)
            return@synchronized
        }
        val start = preferences.getLong("window_start", time)
        val newWindow = time < start || time - start >= WINDOW_MS
        val count = if (newWindow) 0 else preferences.getInt("window_count", 0)
        if (count >= MAX_RESETS) {
            NotificationPipelineLog.event("component_reset_skipped reason=limit")
            onComplete(false)
            return@synchronized
        }
        if (!preferences.edit().putBoolean("pending_enable", true).putLong("last_reset", time)
                .putLong("window_start", if (newWindow) time else start)
                .putInt("window_count", count + 1).commit()) {
            onComplete(false)
            return@synchronized
        }
        resetInProgress = true
        try {
            setEnabled(false)
            // AOSP coalesces DONT_KILL_APP broadcasts for 1s (10s during startup).
            // Keep the disabled state observable before enabling; never block the main thread.
            scheduleRestore(disabledHoldMs) {
                val restored = synchronized(lock) {
                    resetInProgress = false
                    restoreEnabled()
                }
                NotificationPipelineLog.event("component_reset_finished restored=$restored")
                onComplete(restored)
            }
        } catch (error: RuntimeException) {
            resetInProgress = false
            restoreEnabled()
            NotificationPipelineLog.event("component_reset_failed type=${error.javaClass.simpleName}")
            onComplete(false)
        }
    }

    private fun restoreEnabled(): Boolean = try {
        setEnabled(true)
        preferences.edit().putBoolean("pending_enable", false).commit()
    } catch (error: RuntimeException) {
        // Startup and subsequent recovery retry enabling, independently of reset rate limits.
        NotificationPipelineLog.event("component_enable_failed type=${error.javaClass.simpleName}")
        false
    }

    companion object {
        private val lock = Any()
        private var resetInProgress = false
        const val DISABLED_HOLD_MS = 12_000L
        private const val COOLDOWN_MS = 5 * 60_000L
        private const val WINDOW_MS = 60 * 60_000L
        private const val MAX_RESETS = 3

        fun forContext(context: Context): NotificationComponentReset {
            val app = context.applicationContext
            val component = ComponentName(app, PaymentNotificationListener::class.java)
            return NotificationComponentReset(app.getSharedPreferences("notification_component_reset", Context.MODE_PRIVATE), setEnabled = { enabled ->
                if (Build.VERSION.SDK_INT >= 34) {
                    // Newer Android may remove notification access for a disabled component.
                    // The static API works without a live listener and preserves the grant.
                    if (enabled) {
                        // Also restore a journal left by an older OS/app version that used PM.
                        if (app.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                            app.packageManager.setComponentEnabledSetting(component,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                        }
                        NotificationListenerService.requestRebind(component)
                    } else NotificationListenerService.requestUnbind(component)
                } else {
                    app.packageManager.setComponentEnabledSetting(component,
                        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
                }
            }, disabledHoldMs = if (Build.VERSION.SDK_INT >= 34) 1_000L else DISABLED_HOLD_MS)
        }
    }
}
