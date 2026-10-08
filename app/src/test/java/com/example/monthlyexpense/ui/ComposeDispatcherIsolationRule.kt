package com.example.monthlyexpense.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.ContinuationInterceptor
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.util.ReflectionHelpers

/**
 * Compose 1.7.6 retains its main dispatcher across Robolectric cases, while Robolectric 4.14.1
 * clears the looper queue. Drain the original callback outside the Compose rule so a dropped
 * callback cannot leave the global snapshot observer permanently waiting for its dispatch.
 * This keeps the normal snapshot observer and every Compose idling check enabled.
 */
internal class ComposeDispatcherIsolationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            drainDispatcher()
            try { base.evaluate() } finally { drainDispatcher() }
        }
    }

    private fun drainDispatcher() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val dispatcher = requireNotNull(AndroidUiDispatcher.Main[ContinuationInterceptor])
            ReflectionHelpers.getField<Runnable>(dispatcher, "dispatchCallback").run()
            Snapshot.sendApplyNotifications()
        }
    }
}
